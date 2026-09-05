package com.lightai.admin.model;

import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.draft.WriteContext;
import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.client.access.ImpactAnalysis;
import com.lightai.client.access.ImportResult;
import com.lightai.client.access.ProviderModelCommand;
import com.lightai.client.access.ProviderModelDetail;
import com.lightai.client.access.ProviderModelImportCandidate;
import com.lightai.client.access.ProviderModelImportCommand;
import com.lightai.client.access.ProviderModelListItem;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.storage.access.ConfigReferenceQuery;
import com.lightai.storage.access.ObjectRuntimeStateRepository;
import com.lightai.storage.alias.RouteCandidateRepository;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.model.ProviderModelRecord;
import com.lightai.storage.model.ProviderModelRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;



/**
 * Provider Model 服务（BE-014/015）：能力与价格草稿管理、导入、影响分析。
 * 停用导入允许能力缺失；启用必须补齐且 context_window > max_output_tokens（C-014）；
 * 价格仅影响发布后新 Attempt，历史费用不变。模型导入逐对象事务（C-005）。
 */
public class ProviderModelService {

    public static final String ENTITY_TYPE = "PROVIDER_MODEL";
    public static final Set<String> SORTABLE = Set.of(
            "display_name", "model_id", "context_window", "max_output_tokens",
            "support_stream", "input_price", "output_price", "enabled", "updated_at");
    public static final List<Integer> PRICE_UNITS = List.of(1000, 1000000);
    public static final int IMPORT_MAX = 100;

    /** 外部模型列表端口：PROVIDER_API/ADAPTER_PRESET 候选来源（BE-P05 Adapter 注入）。 */
    public interface ModelListPort {
        List<ProviderModelImportCandidate> list(ProviderModelImportCommand command);
    }

    private final DataSource dataSource;
    private final DraftWriteService draftWriteService;
    private final ProviderModelRepository modelRepository;
    private final RouteCandidateRepository candidateRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final ConfigReferenceQuery referenceQuery;
    private final ObjectRuntimeStateRepository runtimeStateRepository;
    private final ImpactService impactService;
    private final ModelListPort modelListPort;
    private final Clock clock;

    public ProviderModelService(DataSource dataSource,
                                DraftWriteService draftWriteService, ProviderModelRepository modelRepository,
                                RouteCandidateRepository candidateRepository,
                                DraftChangeRepository draftChangeRepository,
                                ConfigReferenceQuery referenceQuery,
                                ObjectRuntimeStateRepository runtimeStateRepository,
                                ImpactService impactService, ModelListPort modelListPort, Clock clock) {
        this.draftWriteService = draftWriteService;
        this.dataSource = dataSource;
        this.modelRepository = modelRepository;
        this.candidateRepository = candidateRepository;
        this.draftChangeRepository = draftChangeRepository;
        this.referenceQuery = referenceQuery;
        this.runtimeStateRepository = runtimeStateRepository;
        this.impactService = impactService;
        this.modelListPort = modelListPort;
        this.clock = clock;
    }

    public PageResult<ProviderModelListItem> list(String keyword, UUID providerId, String connectionStatus,
                                                  Boolean supportStream, Boolean enabled,
                                                  ListQuerySupport.ListQuery query) {
        try (Connection connection = dataSource.getConnection()) {
            StringBuilder filterSql = new StringBuilder();
            List<Object> filters = new ArrayList<>();
            appendFilter(filterSql, filters, "provider_id = ?", providerId);
            appendFilter(filterSql, filters, "enabled = ?", enabled);
            if (supportStream != null) {
                filterSql.append(filterSql.length() > 0 ? " AND " : "").append("support_stream = ?");
                filters.add(supportStream);
            }
            if (keyword != null && !keyword.isBlank()) {
                filterSql.append(filterSql.length() > 0 ? " AND " : "")
                        .append("(display_name ILIKE ? OR model_id ILIKE ?)");
                String like = "%" + keyword.trim() + "%";
                filters.add(like);
                filters.add(like);
            }
            String filter = filterSql.toString().trim();
            List<ProviderModelRecord> records = modelRepository.list(
                    connection, filter, filters, query.sort(), query.offset(), query.limit());
            long total = modelRepository.count(connection, filter, filters);

            List<UUID> ids = records.stream().map(ProviderModelRecord::id).toList();
            Map<UUID, ObjectRuntimeStateRepository.RuntimeStateRow> states =
                    runtimeStateRepository.find(connection, ENTITY_TYPE, ids);
            Set<UUID> draftChanged = draftChangeRepository.findExistingEntityIds(connection, ENTITY_TYPE, ids);
            Map<UUID, String> providerNames = providerNames(connection, records.stream().map(ProviderModelRecord::providerId).toList());

            OffsetDateTime now = OffsetDateTime.now(clock);
            List<ProviderModelListItem> items = records.stream()
                    .filter(record -> connectionStatus == null || connectionStatus.isBlank()
                            || connectionStatus.equals(statusOf(states, record.id())))
                    .map(record -> toListItem(record,
                            providerNames.getOrDefault(record.providerId().toString(), ""),
                            states.get(record.id()),
                            draftChanged.contains(record.id())))
                    .toList();
            return new PageResult<>(items, total, query.page(), query.pageSize(), query.sort(), now, now);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "模型列表读取失败");
        }
    }

    public ProviderModelDetail get(UUID id) {
        try (Connection connection = dataSource.getConnection()) {
            ProviderModelRecord record = load(connection, id);
            boolean draftChanged = draftChangeRepository.existsByEntity(connection, ENTITY_TYPE, id);
            return toDetail(record, providerNames(connection, List.of(record.providerId()))
                    .getOrDefault(record.providerId().toString(), ""), null, draftChanged);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "模型详情读取失败");
        }
    }

    public ManagementOperationResult<ProviderModelDetail> create(ProviderModelCommand command, WriteContext ctx) {
        UUID providerId = parseId(command.providerId(), "provider_id");
        validateCommand(command, false);
        String modelId = requireText(command.modelId(), "model_id", 128);
        String displayName = requireText(command.displayName(), "display_name", 64);

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        ProviderModelRecord record = toRecord(command, providerId, modelId, displayName, id, 1L, now, null);

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "CREATE", ENTITY_TYPE, id.toString(), 0L,
                null,
                connection -> {
                    requireProvider(connection, providerId);
                    ensureUnique(connection, providerId, modelId, displayName, null);
                    if (Boolean.TRUE.equals(command.enabled())) {
                        validateCompleteness(command, "启用模型必须补齐能力字段");
                    }
                    modelRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE, id, displayName, "CREATE", 1L, List.of(
                            FieldChange.changed("model_id", null, modelId),
                            FieldChange.changed("display_name", null, displayName),
                            FieldChange.changed("enabled", null, record.enabled())));
                }));
        return new ManagementOperationResult<>(id.toString(), result.entityVersion(), get(id),
                true, result.draftRevision(), ctx.requestId());
    }

    public ManagementOperationResult<ProviderModelDetail> update(UUID id, ProviderModelCommand command,
                                                                 WriteContext ctx) {
        if (command.version() == null) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "version 必填", "version");
        }
        validateCommand(command, true);
        String displayName = requireText(command.displayName(), "display_name", 64);
        long newVersion = command.version() + 1;

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE, id.toString(), command.version(),
                connection -> modelRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    ProviderModelRecord persisted = load(connection, id);
                    if (command.providerId() != null && !persisted.providerId().toString().equals(command.providerId())) {
                        throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE, "模型所属 Provider 不可修改");
                    }
                    if (command.modelId() != null && !persisted.modelId().equals(command.modelId())) {
                        throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE, "model_id 创建后不可修改");
                    }
                    ensureUnique(connection, persisted.providerId(), persisted.modelId(), displayName, id);
                    if (Boolean.TRUE.equals(command.enabled()) && !persisted.enabled()) {
                        validateCompleteness(command, "启用模型必须补齐能力字段");
                    }
                    OffsetDateTime now = OffsetDateTime.now(clock);
                    ProviderModelRecord updated = toRecord(command, persisted.providerId(),
                            persisted.modelId(), displayName, id, newVersion, now, null);
                    updated = new ProviderModelRecord(updated.id(), updated.providerId(), updated.modelId(),
                            updated.displayName(), updated.modelType(), updated.tokenizerFamily(),
                            updated.contextWindow(), updated.maxOutputTokens(), updated.supportStream(),
                            updated.supportSystemMessage(), updated.supportTemperature(), updated.supportTopP(),
                            updated.supportStop(), updated.temperatureMin(), updated.temperatureMax(),
                            updated.topPMin(), updated.topPMax(), updated.maxStopSequences(),
                            updated.maxStopLength(), updated.defaultTemperature(), updated.defaultTopP(),
                            updated.defaultMaxTokens(), updated.defaultStop(), updated.inputPrice(),
                            updated.outputPrice(), updated.priceUnit(), updated.currency(), updated.enabled(),
                            persisted.importSource(), persisted.importAdapterVersion(),
                            newVersion, persisted.createdAt(), now, null);
                    modelRepository.update(connection, updated);
                    return new DraftEntityChange(ENTITY_TYPE, id, displayName, "UPDATE", newVersion, List.of(
                            FieldChange.changed("display_name", persisted.displayName(), displayName),
                            FieldChange.changed("input_price", persisted.inputPrice(), updated.inputPrice()),
                            FieldChange.changed("output_price", persisted.outputPrice(), updated.outputPrice()),
                            FieldChange.changed("enabled", persisted.enabled(), updated.enabled())));
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, get(id),
                true, result.draftRevision(), ctx.requestId());
    }

    /** 逐对象事务导入（C-005）：重复 skipped，单项失败不影响其余成功项。 */
    public ImportResult importModels(ProviderModelImportCommand command, WriteContext ctx) {
        UUID providerId = parseId(command.providerId(), "provider_id");
        List<String> modelIds = command.modelIds();
        if (modelIds == null || modelIds.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "model_ids 非空数组", "model_ids");
        }
        List<String> distinct = List.copyOf(new LinkedHashSet<>(modelIds));
        if (distinct.size() > IMPORT_MAX) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "model_ids 最多 " + IMPORT_MAX + " 项", "model_ids");
        }
        boolean applyDefaults = command.applyKnownDefaults() == null || command.applyKnownDefaults();
        boolean enabled = Boolean.TRUE.equals(command.enabled());
        if (enabled && !applyDefaults) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "未知能力不可 enabled，请先补齐能力字段", "enabled");
        }

        List<ProviderModelImportCandidate> candidates = modelListPort.list(command);
        Map<String, ProviderModelImportCandidate> byModelId = new java.util.HashMap<>();
        for (ProviderModelImportCandidate candidate : candidates) {
            byModelId.putIfAbsent(candidate.modelId(), candidate);
        }

        List<ImportResult.CreatedEntry> created = new ArrayList<>();
        List<ImportResult.SkippedEntry> skipped = new ArrayList<>();
        List<ImportResult.FailedEntry> failed = new ArrayList<>();
        for (String modelId : distinct) {
            try {
                created.add(importOne(providerId, modelId, byModelId.get(modelId), applyDefaults, enabled, ctx));
            } catch (LightAiException e) {
                if (e.code() == ErrorCode.OBJECT_NOT_FOUND) {
                    skipped.add(new ImportResult.SkippedEntry(modelId, "ALREADY_EXISTS"));
                } else {
                    failed.add(new ImportResult.FailedEntry(modelId, e.code().name()));
                }
            } catch (Exception e) {
                failed.add(new ImportResult.FailedEntry(modelId, ErrorCode.INTERNAL_ERROR.name()));
            }
        }
        return new ImportResult(created, skipped, failed);
    }

    public List<ProviderModelImportCandidate> availableModels(UUID providerId, String source,
                                                              UUID credentialId, String keyword) {
        try (Connection connection = dataSource.getConnection()) {
            ConfigReferenceQuery.ProviderSummary provider = referenceQuery.findProviderSummary(connection, providerId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "Provider 不存在或已删除"));
            ProviderModelImportCommand probe = new ProviderModelImportCommand(
                    providerId.toString(), source, credentialId == null ? null : credentialId.toString(),
                    List.of(), true, false);
            List<ProviderModelImportCandidate> candidates;
            try {
                candidates = modelListPort.list(probe);
            } catch (LightAiException e) {
                throw e;
            } catch (Exception e) {
                throw new LightAiException(ErrorCode.MODEL_LIST_NOT_SUPPORTED, "模型列表读取失败");
            }
            if (keyword == null || keyword.isBlank()) {
                return candidates;
            }
            String key = keyword.trim().toLowerCase();
            return candidates.stream()
                    .filter(candidate -> candidate.modelId().toLowerCase().contains(key)
                            || (candidate.displayName() != null && candidate.displayName().toLowerCase().contains(key)))
                    .toList();
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "模型候选读取失败");
        }
    }

    public ManagementOperationResult<ProviderModelDetail> changeEnabled(UUID id, boolean enable, long version,
                                                                        String confirmedImpactVersion,
                                                                        WriteContext ctx) {
        if (!enable && confirmedImpactVersion != null && !confirmedImpactVersion.isBlank()) {
            assertImpactConfirmed(id, confirmedImpactVersion);
        }
        long newVersion = version + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                enable ? "ENABLE" : "DISABLE", ENTITY_TYPE, id.toString(), version,
                connection -> modelRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    ProviderModelRecord persisted = load(connection, id);
                    if (enable) {
                        validateRecordCompleteness(persisted, "启用模型必须补齐能力字段");
                    }
                    OffsetDateTime now = OffsetDateTime.now(clock);
                    modelRepository.update(connection, new ProviderModelRecord(
                            persisted.id(), persisted.providerId(), persisted.modelId(), persisted.displayName(),
                            persisted.modelType(), persisted.tokenizerFamily(), persisted.contextWindow(),
                            persisted.maxOutputTokens(), persisted.supportStream(), persisted.supportSystemMessage(),
                            persisted.supportTemperature(), persisted.supportTopP(), persisted.supportStop(),
                            persisted.temperatureMin(), persisted.temperatureMax(), persisted.topPMin(),
                            persisted.topPMax(), persisted.maxStopSequences(), persisted.maxStopLength(),
                            persisted.defaultTemperature(), persisted.defaultTopP(), persisted.defaultMaxTokens(),
                            persisted.defaultStop(), persisted.inputPrice(), persisted.outputPrice(),
                            persisted.priceUnit(), persisted.currency(), enable,
                            persisted.importSource(), persisted.importAdapterVersion(),
                            newVersion, persisted.createdAt(), now, null));
                    return new DraftEntityChange(ENTITY_TYPE, id, persisted.displayName(),
                            enable ? "ENABLE" : "DISABLE", newVersion, List.of(
                            FieldChange.changed("enabled", persisted.enabled(), enable)));
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, get(id),
                true, result.draftRevision(), ctx.requestId());
    }

    public ManagementOperationResult<ProviderModelDetail> delete(UUID id, long version,
                                                                 String confirmedImpactVersion, WriteContext ctx) {
        assertImpactConfirmed(id, confirmedImpactVersion);
        long newVersion = version + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "DELETE", ENTITY_TYPE, id.toString(), version,
                connection -> modelRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    ProviderModelRecord persisted = load(connection, id);
                    if (!candidateRepository.findAliveByModelIds(connection, List.of(persisted.id())).isEmpty()) {
                        throw new LightAiException(ErrorCode.OBJECT_IN_USE, "模型仍被候选引用，不能删除");
                    }
                    OffsetDateTime now = OffsetDateTime.now(clock);
                    modelRepository.update(connection, new ProviderModelRecord(
                            persisted.id(), persisted.providerId(), persisted.modelId(), persisted.displayName(),
                            persisted.modelType(), persisted.tokenizerFamily(), persisted.contextWindow(),
                            persisted.maxOutputTokens(), persisted.supportStream(), persisted.supportSystemMessage(),
                            persisted.supportTemperature(), persisted.supportTopP(), persisted.supportStop(),
                            persisted.temperatureMin(), persisted.temperatureMax(), persisted.topPMin(),
                            persisted.topPMax(), persisted.maxStopSequences(), persisted.maxStopLength(),
                            persisted.defaultTemperature(), persisted.defaultTopP(), persisted.defaultMaxTokens(),
                            persisted.defaultStop(), persisted.inputPrice(), persisted.outputPrice(),
                            persisted.priceUnit(), persisted.currency(), persisted.enabled(),
                            persisted.importSource(), persisted.importAdapterVersion(),
                            newVersion, persisted.createdAt(), now, now));
                    return new DraftEntityChange(ENTITY_TYPE, id, persisted.displayName(), "DELETE", newVersion,
                            List.of());
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, null,
                true, result.draftRevision(), ctx.requestId());
    }

    public ImpactAnalysis impact(UUID id) {
        try (Connection connection = dataSource.getConnection()) {
            load(connection, id);
            return impactService.analyze(connection, ENTITY_TYPE, id);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响分析读取失败");
        }
    }

    /** 单对象导入：走草稿事务（锁+差异+revision+同事务审计），重复对象跳过（C-005）。 */
    private ImportResult.CreatedEntry importOne(UUID providerId, String modelId,
                                                ProviderModelImportCandidate candidate,
                                                boolean applyDefaults, boolean enabled, WriteContext ctx) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String displayName = candidate != null && candidate.displayName() != null
                ? candidate.displayName() : modelId;
        try {
            DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                    ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                    "CREATE", ENTITY_TYPE, id.toString(), 0L,
                    null,
                    connection -> {
                        requireProvider(connection, providerId);
                        if (modelRepository.existsAliveByModelId(connection, providerId, modelId)) {
                            throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "已存在，跳过");
                        }
                        ProviderModelRecord record = new ProviderModelRecord(
                                id, providerId, modelId, displayName, "CHAT_TEXT",
                                applyDefaults && candidate != null ? candidate.tokenizerFamily() : null,
                                applyDefaults && candidate != null ? candidate.contextWindow() : null,
                                applyDefaults && candidate != null ? candidate.maxOutputTokens() : null,
                                applyDefaults && candidate != null ? candidate.supportStream() : null,
                                applyDefaults && candidate != null ? candidate.supportSystemMessage() : null,
                                applyDefaults && candidate != null ? candidate.supportTemperature() : null,
                                applyDefaults && candidate != null ? candidate.supportTopP() : null,
                                applyDefaults && candidate != null ? candidate.supportStop() : null,
                                null, null, null, null, null, null, null, null, null, List.of(),
                                price(candidate == null ? null : candidate.inputPrice()),
                                price(candidate == null ? null : candidate.outputPrice()),
                                1000000,
                                candidate == null || candidate.currency() == null ? "USD" : candidate.currency(),
                                enabled, "PROVIDER_API", null, 1L, now, now, null);
                        modelRepository.insert(connection, record);
                        return new DraftEntityChange(ENTITY_TYPE, id, displayName, "CREATE", 1L, List.of(
                                FieldChange.changed("model_id", null, modelId),
                                FieldChange.changed("import_source", null, "PROVIDER_API"),
                                FieldChange.changed("enabled", null, enabled)));
                    }));
            return new ImportResult.CreatedEntry(modelId, id.toString(), result.entityVersion());
        } catch (LightAiException e) {
            if (e.code() == ErrorCode.OBJECT_NOT_FOUND) {
                throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "已存在，跳过");
            }
            throw e;
        }
    }

    private void validateCommand(ProviderModelCommand command, boolean isUpdate) {
        if (command.inputPrice() != null && command.inputPrice().signum() < 0) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "input_price 不可为负", "input_price");
        }
        if (command.outputPrice() != null && command.outputPrice().signum() < 0) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "output_price 不可为负", "output_price");
        }
        if (command.priceUnit() != null && !PRICE_UNITS.contains(command.priceUnit())) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "price_unit 仅支持 1000/1000000", "price_unit");
        }
        if (command.currency() != null && command.currency().length() != 3) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "currency 必须为 ISO4217 三字母", "currency");
        }
        if (command.contextWindow() != null && command.maxOutputTokens() != null
                && command.contextWindow() <= command.maxOutputTokens()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "context_window 必须大于 max_output_tokens", "context_window");
        }
        if (command.defaultMaxTokens() != null && command.maxOutputTokens() != null
                && command.defaultMaxTokens() > command.maxOutputTokens()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "default_max_tokens 不得超过 max_output_tokens", "default_max_tokens");
        }
        if (command.defaultStop() != null && command.defaultStop().stream().distinct().count() != command.defaultStop().size()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "default_stop 不允许重复", "default_stop");
        }
    }

    /** 启用完整性（C-014）：tokenizer、上下文、五项能力、范围与默认值全部补齐。 */
    private void validateCompleteness(ProviderModelCommand command, String message) {
        List<String> missing = new ArrayList<>();
        if (isBlank(command.tokenizerFamily())) {
            missing.add("tokenizer_family");
        }
        if (command.contextWindow() == null || command.maxOutputTokens() == null
                || command.contextWindow() <= command.maxOutputTokens()) {
            missing.add("context_window");
        }
        for (Boolean flag : new Boolean[]{command.supportStream(), command.supportSystemMessage(),
                command.supportTemperature(), command.supportTopP(), command.supportStop()}) {
            if (flag == null) {
                missing.add("support_*");
            }
        }
        if (command.supportTemperature() == Boolean.TRUE
                && (command.temperatureMin() == null || command.temperatureMax() == null)) {
            missing.add("temperature range");
        }
        if (command.supportTopP() == Boolean.TRUE && (command.topPMin() == null || command.topPMax() == null)) {
            missing.add("top_p range");
        }
        if (command.supportStop() == Boolean.TRUE
                && (command.maxStopSequences() == null || command.maxStopLength() == null)) {
            missing.add("stop limits");
        }
        if (!missing.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, message + ": " + missing);
        }
    }

    private void validateRecordCompleteness(ProviderModelRecord record, String message) {
        List<String> missing = new ArrayList<>();
        if (isBlank(record.tokenizerFamily())) {
            missing.add("tokenizer_family");
        }
        if (record.contextWindow() == null || record.maxOutputTokens() == null
                || record.contextWindow() <= record.maxOutputTokens()) {
            missing.add("context_window");
        }
        for (Boolean flag : new Boolean[]{record.supportStream(), record.supportSystemMessage(),
                record.supportTemperature(), record.supportTopP(), record.supportStop()}) {
            if (flag == null) {
                missing.add("support_*");
            }
        }
        if (!missing.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, message + ": " + missing);
        }
    }

    private ProviderModelRecord toRecord(ProviderModelCommand command, UUID providerId, String modelId,
                                         String displayName, UUID id, long version, OffsetDateTime now,
                                         OffsetDateTime deletedAt) {
        return new ProviderModelRecord(
                id, providerId, modelId, displayName, "CHAT_TEXT",
                command.tokenizerFamily(), command.contextWindow(), command.maxOutputTokens(),
                command.supportStream(), command.supportSystemMessage(), command.supportTemperature(),
                command.supportTopP(), command.supportStop(),
                command.temperatureMin(), command.temperatureMax(), command.topPMin(), command.topPMax(),
                command.maxStopSequences(), command.maxStopLength(),
                command.defaultTemperature(), command.defaultTopP(), command.defaultMaxTokens(),
                command.defaultStop() == null ? List.of() : List.copyOf(command.defaultStop()),
                command.inputPrice(), command.outputPrice(),
                command.priceUnit() == null ? 1000000 : command.priceUnit(),
                command.currency() == null ? "USD" : command.currency(),
                command.enabled() != null && command.enabled(),
                null, null,
                version, now, now, deletedAt);
    }

    private void ensureUnique(Connection connection, UUID providerId, String modelId,
                              String displayName, UUID selfId) {
        List<ProviderModelRecord> byModelId = modelRepository.list(connection,
                "provider_id = ? AND model_id = ?", List.of(providerId, modelId), "id asc", 0, 2);
        if (byModelId.stream().anyMatch(record -> !record.id().equals(selfId))) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "同 Provider 下 model_id 已存在", "model_id");
        }
        List<ProviderModelRecord> byDisplayName = modelRepository.list(connection,
                "provider_id = ? AND display_name = ?", List.of(providerId, displayName), "id asc", 0, 2);
        if (byDisplayName.stream().anyMatch(record -> !record.id().equals(selfId))) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "同 Provider 下 display_name 已存在", "display_name");
        }
    }

    private void assertImpactConfirmed(UUID id, String confirmedImpactVersion) {
        try (Connection connection = dataSource.getConnection()) {
            impactService.assertConfirmed(connection, ENTITY_TYPE, id, confirmedImpactVersion);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响分析读取失败");
        }
    }

    private ProviderModelRecord load(Connection connection, UUID id) {
        return modelRepository.find(connection, id)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "模型不存在或已删除"));
    }

    private void requireProvider(Connection connection, UUID providerId) {
        referenceQuery.findProviderSummary(connection, providerId)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID, "Provider 不存在或已删除"));
    }

    private Map<UUID, String> providerNames(Connection connection, List<UUID> providerIds) {
        Map<UUID, String> names = new java.util.HashMap<>();
        for (UUID providerId : new LinkedHashSet<>(providerIds)) {
            referenceQuery.findProviderSummary(connection, providerId)
                    .ifPresent(summary -> names.put(providerId, summary.name()));
        }
        return names;
    }

    private static ProviderModelListItem toListItem(ProviderModelRecord record, String providerName,
                                                    ObjectRuntimeStateRepository.RuntimeStateRow state,
                                                    boolean draftChanged) {
        return new ProviderModelListItem(
                record.id().toString(), record.providerId().toString(), providerName,
                record.displayName(), record.modelId(), record.contextWindow(), record.maxOutputTokens(),
                record.supportStream(),
                record.inputPrice() == null ? null : record.inputPrice().toPlainString(),
                record.outputPrice() == null ? null : record.outputPrice().toPlainString(),
                record.currency(),
                state == null || state.connectionStatus() == null ? "UNKNOWN" : state.connectionStatus(),
                state == null ? null : state.lastCheckedAt(),
                0,
                record.enabled(), draftChanged, record.updatedAt());
    }

    private static ProviderModelDetail toDetail(ProviderModelRecord record, String providerName,
                                                ObjectRuntimeStateRepository.RuntimeStateRow state,
                                                boolean draftChanged) {
        return new ProviderModelDetail(
                record.id().toString(), record.providerId().toString(), providerName,
                record.modelId(), record.displayName(), record.modelType(), record.tokenizerFamily(),
                record.contextWindow(), record.maxOutputTokens(),
                record.supportStream(), record.supportSystemMessage(), record.supportTemperature(),
                record.supportTopP(), record.supportStop(),
                record.temperatureMin(), record.temperatureMax(), record.topPMin(), record.topPMax(),
                record.maxStopSequences(), record.maxStopLength(),
                record.defaultTemperature(), record.defaultTopP(), record.defaultMaxTokens(), record.defaultStop(),
                record.inputPrice() == null ? null : record.inputPrice().toPlainString(),
                record.outputPrice() == null ? null : record.outputPrice().toPlainString(),
                record.priceUnit(), record.currency(), record.enabled(),
                record.importSource(), record.importAdapterVersion(),
                state == null || state.connectionStatus() == null ? "UNKNOWN" : state.connectionStatus(),
                state == null ? null : state.lastCheckedAt(),
                draftChanged, record.createdAt(), record.updatedAt(), record.version());
    }

    private static String statusOf(Map<UUID, ObjectRuntimeStateRepository.RuntimeStateRow> states, UUID id) {
        ObjectRuntimeStateRepository.RuntimeStateRow state = states.get(id);
        return state == null || state.connectionStatus() == null ? "UNKNOWN" : state.connectionStatus();
    }

    private static BigDecimal price(String value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static void appendFilter(StringBuilder sql, List<Object> filters, String clause, Object value) {
        if (value != null) {
            sql.append(sql.length() > 0 ? " AND " : "").append(clause);
            filters.add(value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 长度 1—"
                    + max, field);
        }
        return value.trim();
    }

    private static UUID parseId(String value, String field) {
        if (value == null) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 必填", field);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 不是合法ID", field);
        }
    }
}
