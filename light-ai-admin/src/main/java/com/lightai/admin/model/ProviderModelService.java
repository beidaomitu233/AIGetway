package com.lightai.admin.model;

import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.provider.ProviderService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ImpactConfirmCommand;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.model.ProviderModelDetail;
import com.lightai.client.model.ProviderModelSaveCommand;
import com.lightai.client.paging.PageResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.storage.alias.JdbcCandidateRepository;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.model.JdbcProviderModelRepository;
import com.lightai.storage.model.ProviderModelRecord;
import com.lightai.storage.pool.JdbcPoolRepository;
import com.lightai.storage.provider.JdbcProviderRepository;
import com.lightai.storage.provider.ProviderRecord;
import com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Provider Model 管理服务（BE-014）。
 * 停用状态允许能力缺失（导入阶段），启用与发布要求完整（C-014）；
 * context 严格大于 max_output；价格非负且金额精度由字符串传输保证；
 * 被候选引用时不能删除（OBJECT_IN_USE），历史价格不变（价格快照随 Attempt）。
 */
public class ProviderModelService {

    public static final String ENTITY_TYPE = "PROVIDER_MODEL";
    private static final Set<String> SORTABLE = Set.of("model_id", "display_name", "updated_at", "created_at");

    private final DataSource dataSource;
    private final JdbcProviderModelRepository modelRepository;
    private final JdbcProviderRepository providerRepository;
    private final JdbcCandidateRepository candidateRepository;
    private final JdbcObjectRuntimeStateRepository runtimeStateRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final DraftWriteService draftWriteService;
    private final ImpactService impactService;
    private final PageResultFactory pageResultFactory;
    private final String sourceMode;
    private final com.lightai.storage.check.JdbcProviderCheckRecordRepository modelCheckRecordRepository;
    private final com.lightai.storage.runtime.JdbcRuntimeStateWriter modelRuntimeStateWriter;
    private final List<com.lightai.spi.check.ProviderCheckExecutor> modelCheckExecutors;

    public ProviderModelService(DataSource dataSource, JdbcProviderModelRepository modelRepository,
                                JdbcProviderRepository providerRepository,
                                JdbcCandidateRepository candidateRepository,
                                JdbcObjectRuntimeStateRepository runtimeStateRepository,
                                DraftChangeRepository draftChangeRepository,
                                DraftWriteService draftWriteService, ImpactService impactService,
                                PageResultFactory pageResultFactory, String sourceMode) {
        this(dataSource, modelRepository, providerRepository, candidateRepository,
                runtimeStateRepository, draftChangeRepository, draftWriteService, impactService,
                pageResultFactory, sourceMode,
                new com.lightai.storage.check.JdbcProviderCheckRecordRepository(
                        com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME),
                new com.lightai.storage.runtime.JdbcRuntimeStateWriter(
                        com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME),
                List.of());
    }

    public ProviderModelService(DataSource dataSource, JdbcProviderModelRepository modelRepository,
                                JdbcProviderRepository providerRepository,
                                JdbcCandidateRepository candidateRepository,
                                JdbcObjectRuntimeStateRepository runtimeStateRepository,
                                DraftChangeRepository draftChangeRepository,
                                DraftWriteService draftWriteService, ImpactService impactService,
                                PageResultFactory pageResultFactory, String sourceMode,
                                com.lightai.storage.check.JdbcProviderCheckRecordRepository modelCheckRecordRepository,
                                com.lightai.storage.runtime.JdbcRuntimeStateWriter modelRuntimeStateWriter,
                                List<com.lightai.spi.check.ProviderCheckExecutor> modelCheckExecutors) {
        this.dataSource = dataSource;
        this.modelRepository = modelRepository;
        this.providerRepository = providerRepository;
        this.candidateRepository = candidateRepository;
        this.runtimeStateRepository = runtimeStateRepository;
        this.draftChangeRepository = draftChangeRepository;
        this.draftWriteService = draftWriteService;
        this.impactService = impactService;
        this.pageResultFactory = pageResultFactory;
        this.sourceMode = sourceMode;
        this.modelCheckRecordRepository = modelCheckRecordRepository;
        this.modelRuntimeStateWriter = modelRuntimeStateWriter;
        this.modelCheckExecutors = modelCheckExecutors == null ? List.of() : List.copyOf(modelCheckExecutors);
    }

    // ---------- 读取 ----------

    public PageResult<ProviderModelDetail> listByProvider(RequestContext context, UUID providerId,
                                                          Map<String, String> params) {
        RequestPermissions.require(context, Permissions.MODEL_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(
                params.get("page"), params.get("page_size"), params.get("sort"),
                SORTABLE, "model_id asc");
        try (Connection connection = dataSource.getConnection()) {
            requireProviderLive(connection, providerId);
            List<ProviderModelRecord> records = modelRepository.listByProvider(connection, providerId,
                    params.get("keyword"), parseBoolean(params.get("support_stream")),
                    parseBoolean(params.get("enabled")), query.sort(), query.limit(),
                    (int) query.offset());
            long total = modelRepository.countByProvider(connection, providerId,
                    params.get("keyword"), parseBoolean(params.get("support_stream")),
                    parseBoolean(params.get("enabled")));
            List<ProviderModelDetail> items = new ArrayList<>(records.size());
            for (ProviderModelRecord record : records) {
                items.add(toDetail(connection, record));
            }
            return pageResultFactory.create(items, total, query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "模型列表当前无法读取");
        }
    }

    public ProviderModelDetail detail(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.MODEL_VIEW);
        UUID id = ProviderService.parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            ProviderModelRecord record = modelRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "模型不存在或已删除"));
            return toDetail(connection, record);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "模型详情当前无法读取");
        }
    }

    // ---------- 写入（BE-014） ----------

    public ManagementOperationResult<ProviderModelDetail> create(RequestContext context, UUID providerId,
                                                                 ProviderModelSaveCommand command) {
        RequestPermissions.require(context, Permissions.MODEL_MANAGE);
        UUID id = UUID.randomUUID();
        String requestId = context.requestId();
        validateCommand(command, true);

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "CREATE", ENTITY_TYPE.toLowerCase(), providerId.toString(), 0, null,
                connection -> {
                    ProviderRecord provider = requireProviderLive(connection, providerId);
                    ProviderModelRecord record = new ProviderModelRecord(id, providerId,
                            id.toString(), command.displayName().strip(), "CHAT_TEXT",
                            command.tokenizerFamily(), command.contextWindow(), command.maxOutputTokens(),
                            command.supportStream(), command.supportSystemMessage(),
                            command.supportTemperature(), command.supportTopP(), command.supportStop(),
                            command.temperatureMin(), command.temperatureMax(), command.topPMin(),
                            command.topPMax(), command.maxStopSequences(), command.maxStopLength(),
                            command.defaultTemperature(), command.defaultTopP(), command.defaultMaxTokens(),
                            command.defaultStop(), command.inputPrice(), command.outputPrice(),
                            command.priceUnit(), command.currency(), command.enabled(),
                            null, null, 1L, OffsetDateTime.now(), OffsetDateTime.now());
                    modelRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id,
                            command.displayName(), "CREATE", 1L, List.of(
                            FieldChange.changed("display_name", null, command.displayName()),
                            FieldChange.changed("enabled", null, command.enabled())));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ProviderModelRecord record = modelRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "创建结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "创建结果读取失败");
        }
    }

    public ManagementOperationResult<ProviderModelDetail> update(RequestContext context, String rawId,
                                                                 ProviderModelSaveCommand command) {
        RequestPermissions.require(context, Permissions.MODEL_MANAGE);
        UUID id = ProviderService.parseId(rawId);
        validateCommand(command, command.version() != null && command.enabled());
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE.toLowerCase(), id.toString(),
                requireVersion(command.version()),
                connection -> modelRepository.lockLiveById(connection, id)
                        .map(ProviderModelRecord::version).orElse(null),
                connection -> {
                    ProviderModelRecord current = modelRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "模型不存在或已删除"));
                    ProviderModelRecord saved = modelRepository.update(connection, new ProviderModelRecord(
                            current.id(), current.providerId(), current.modelId(),
                            command.displayName().strip(), current.modelType(),
                            command.tokenizerFamily(), command.contextWindow(), command.maxOutputTokens(),
                            command.supportStream(), command.supportSystemMessage(),
                            command.supportTemperature(), command.supportTopP(), command.supportStop(),
                            command.temperatureMin(), command.temperatureMax(), command.topPMin(),
                            command.topPMax(), command.maxStopSequences(), command.maxStopLength(),
                            command.defaultTemperature(), command.defaultTopP(), command.defaultMaxTokens(),
                            command.defaultStop(), command.inputPrice(), command.outputPrice(),
                            command.priceUnit(), command.currency(), command.enabled(),
                            current.importSource(), current.importAdapterVersion(),
                            current.version(), current.createdAt(), current.updatedAt()));
                    List<FieldChange> changes = new ArrayList<>();
                    if (!current.displayName().equals(command.displayName())) {
                        changes.add(FieldChange.changed("display_name", current.displayName(),
                                command.displayName()));
                    }
                    if (current.enabled() != command.enabled()) {
                        changes.add(FieldChange.changed("enabled", current.enabled(), command.enabled()));
                    }
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id,
                            command.displayName(), "UPDATE", saved.version(), List.copyOf(changes));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ProviderModelRecord record = modelRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "更新结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "更新结果读取失败");
        }
    }

    public ManagementOperationResult<ProviderModelDetail> setEnabled(RequestContext context,
                                                                     String rawId, boolean enabled,
                                                                     Long version,
                                                                     String confirmedImpactVersion) {
        RequestPermissions.require(context, Permissions.MODEL_MANAGE);
        UUID id = ProviderService.parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        try (Connection connection = dataSource.getConnection()) {
            ProviderModelRecord current = modelRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "模型不存在或已删除"));
            if (enabled) {
                // 启用要求能力完整且 context 严格大于 max_output（C-014）
                ProviderModelDetail detail = toDetail(connection, current);
                if (!detail.capabilitiesComplete()) {
                    throw fieldError("capabilities", "INCOMPLETE",
                            "启用前必须补齐 tokenizer/context/能力声明");
                }
                if (!detail.contextWindowValid()) {
                    throw fieldError("context_window", "INVALID", "context_window 必须大于 max_output_tokens");
                }
            } else {
                impactService.verifyConfirmedImpact(confirmedImpactVersion,
                        impact(connection, current));
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "模型状态校验失败");
        }

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                enabled ? "ENABLE" : "DISABLE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> modelRepository.lockLiveById(connection, id)
                        .map(ProviderModelRecord::version).orElse(null),
                connection -> {
                    ProviderModelRecord current = modelRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "模型不存在或已删除"));
                    ProviderModelRecord saved = modelRepository.update(connection,
                            withEnabled(current, enabled));
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.displayName(),
                            enabled ? "ENABLE" : "DISABLE", saved.version(),
                            List.of(FieldChange.changed("enabled", current.enabled(), enabled)));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ProviderModelRecord record = modelRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "操作结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "操作结果读取失败");
        }
    }

    public ManagementOperationResult<ProviderModelDetail> delete(RequestContext context, String rawId,
                                                                 Long version,
                                                                 String confirmedImpactVersion) {
        RequestPermissions.require(context, Permissions.MODEL_MANAGE);
        UUID id = ProviderService.parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        try (Connection connection = dataSource.getConnection()) {
            ProviderModelRecord current = modelRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "模型不存在或已删除"));
            impactService.verifyConfirmedImpact(confirmedImpactVersion, impact(connection, current));
            if (candidateRepository.countLiveByProviderModel(connection, id) > 0) {
                throw new LightAiException(ErrorCode.OBJECT_IN_USE, "模型仍被候选引用，不能删除");
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "模型状态校验失败");
        }

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "DELETE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> modelRepository.lockLiveById(connection, id)
                        .map(ProviderModelRecord::version).orElse(null),
                connection -> {
                    ProviderModelRecord current = modelRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "模型不存在或已删除"));
                    modelRepository.markDeleted(connection, id);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.displayName(),
                            "DELETE", current.version(), List.of());
                }));

        return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                null, true, result.draftRevision(), requestId);
    }

    public com.lightai.client.impact.ImpactAnalysis impact(RequestContext context, String rawId,
                                                           String operation) {
        RequestPermissions.require(context, Permissions.MODEL_MANAGE);
        validateOperation(operation);
        UUID id = ProviderService.parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            ProviderModelRecord record = modelRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "模型不存在或已删除"));
            return impact(connection, record);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响分析当前无法读取");
        }
    }

    private com.lightai.client.impact.ImpactAnalysis impact(Connection connection,
                                                            ProviderModelRecord record) {
        // 模型的引用关系：引用它的候选（其 Alias 由候选的 alias_id 关联）
        List<com.lightai.client.impact.ImpactReference> references = new ArrayList<>();
        var referencing = candidateRepository.findLiveByProviderModel(connection, record.id());
        List<UUID> affectedAliases = referencing.stream()
                .map(com.lightai.storage.alias.CandidateRecord::aliasId).distinct().toList();
        for (var candidate : referencing) {
            references.add(new com.lightai.client.impact.ImpactReference(
                    "route_candidate", candidate.id().toString(),
                    "candidate:" + candidate.id(), "CANDIDATE_REFERENCE"));
        }
        boolean canDelete = referencing.isEmpty();
        return new com.lightai.client.impact.ImpactAnalysis(
                ImpactService.computeVersion(ENTITY_TYPE.toLowerCase(), record.id(), references,
                        affectedAliases),
                ENTITY_TYPE.toLowerCase(), record.id().toString(), references,
                affectedAliases.stream().map(UUID::toString).sorted().toList(),
                canDelete, canDelete ? List.of()
                : references.stream().map(reference ->
                        reference.entityType() + ":" + reference.name()).toList());
    }

    // ---------- 校验 ----------

    private void validateCommand(ProviderModelSaveCommand command, boolean requireCompleteIfEnabled) {
        if (command.displayName() == null || command.displayName().strip().length() < 2
                || command.displayName().strip().length() > 64) {
            throw fieldError("display_name", "INVALID", "display_name 长度 2—64");
        }
        if (command.enabled()) {
            if (!completeForEnable(command)) {
                throw fieldError("capabilities", "INCOMPLETE",
                        "启用状态必须声明 tokenizer/context/能力（C-014）");
            }
            if (command.contextWindow() <= command.maxOutputTokens()) {
                throw fieldError("context_window", "INVALID", "context_window 必须大于 max_output_tokens");
            }
            validateDefaultValues(command);
        }
        validatePrices(command);
    }

    private boolean completeForEnable(ProviderModelSaveCommand command) {
        return command.tokenizerFamily() != null && !command.tokenizerFamily().isBlank()
                && command.contextWindow() != null && command.contextWindow() > 0
                && command.maxOutputTokens() != null && command.maxOutputTokens() > 0
                && command.supportStream() != null && command.supportSystemMessage() != null
                && command.supportTemperature() != null && command.supportTopP() != null
                && command.supportStop() != null;
    }

    private void validateDefaultValues(ProviderModelSaveCommand command) {
        if (command.defaultMaxTokens() != null) {
            if (command.defaultMaxTokens() < 1
                    || command.defaultMaxTokens() > command.maxOutputTokens()) {
                throw fieldError("default_max_tokens", "INVALID",
                        "default_max_tokens 范围 1—max_output_tokens");
            }
        }
        if (command.supportStop() != null && command.supportStop()) {
            if (command.maxStopSequences() != null
                    && (command.maxStopSequences() < 1 || command.maxStopSequences() > 4)) {
                throw fieldError("max_stop_sequences", "OUT_OF_RANGE", "stop 数量上限 1—4");
            }
            if (command.maxStopLength() != null && (command.maxStopLength() < 1
                    || command.maxStopLength() > 128)) {
                throw fieldError("max_stop_length", "OUT_OF_RANGE", "stop 长度上限 1—128");
            }
        }
    }

    private void validatePrices(ProviderModelSaveCommand command) {
        if ((command.inputPrice() != null && command.inputPrice().signum() < 0)
                || (command.outputPrice() != null && command.outputPrice().signum() < 0)) {
            throw fieldError("input_price", "INVALID", "价格不允许为负数");
        }
        if (command.currency() != null && command.currency().length() != 3) {
            throw fieldError("currency", "INVALID", "currency 为 ISO4217 三位码");
        }
        if (command.priceUnit() != null && command.priceUnit() != 1000 && command.priceUnit() != 1000000) {
            throw fieldError("price_unit", "INVALID", "price_unit 仅允许 1000 或 1000000");
        }
    }

    private ProviderModelRecord withEnabled(ProviderModelRecord current, boolean enabled) {
        return new ProviderModelRecord(current.id(), current.providerId(), current.modelId(),
                current.displayName(), current.modelType(), current.tokenizerFamily(),
                current.contextWindow(), current.maxOutputTokens(), current.supportStream(),
                current.supportSystemMessage(), current.supportTemperature(), current.supportTopP(),
                current.supportStop(), current.temperatureMin(), current.temperatureMax(),
                current.topPMin(), current.topPMax(), current.maxStopSequences(),
                current.maxStopLength(), current.defaultTemperature(), current.defaultTopP(),
                current.defaultMaxTokens(), current.defaultStop(), current.inputPrice(),
                current.outputPrice(), current.priceUnit(), current.currency(), enabled,
                current.importSource(), current.importAdapterVersion(), current.version(),
                current.createdAt(), current.updatedAt());
    }

    private ProviderModelDetail toDetail(Connection connection, ProviderModelRecord record) {
        var state = runtimeStateRepository.findByEntity(connection, "PROVIDER_MODEL",
                record.id()).orElse(null);
        String providerName = providerRepository.findLiveById(connection, record.providerId())
                .map(ProviderRecord::name).orElse("");
        return new ProviderModelDetail(
                record.id().toString(), record.providerId().toString(), providerName,
                record.modelId(), record.displayName(), record.modelType(), record.tokenizerFamily(),
                record.contextWindow(), record.maxOutputTokens(), record.supportStream(),
                record.supportSystemMessage(), record.supportTemperature(), record.supportTopP(),
                record.supportStop(), record.temperatureMin(), record.temperatureMax(),
                record.topPMin(), record.topPMax(), record.maxStopSequences(), record.maxStopLength(),
                record.defaultTemperature(), record.defaultTopP(), record.defaultMaxTokens(),
                record.defaultStop(), record.inputPrice(), record.outputPrice(), record.priceUnit(),
                record.currency(), record.enabled(), record.importSource(),
                record.importAdapterVersion(),
                state == null ? "UNKNOWN"
                        : (state.connectionStatus() == null ? "UNKNOWN" : state.connectionStatus()),
                draftChangeRepository.findChangedEntityIds(connection, ENTITY_TYPE,
                        List.of(record.id())).contains(record.id()),
                record.version(), record.createdAt(), record.updatedAt());
    }

    private ProviderRecord requireProviderLive(Connection connection, UUID providerId) {
        return providerRepository.findLiveById(connection, providerId)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                        "引用的 Provider 不存在或已删除"));
    }

    /** 模型检测（BE-014）：目标锁定该模型与其 Provider，记录 target_type=PROVIDER_MODEL。 */
    public com.lightai.client.provider.ProviderCheckRecord check(RequestContext context, String rawId,
                                                                 com.lightai.client.provider.ProviderCheckCommand command) {
        RequestPermissions.require(context, Permissions.PROVIDER_CHECK);
        UUID id = ProviderService.parseId(rawId);
        OffsetDateTime startedAt = OffsetDateTime.now();
        ProviderModelRecord model;
        ProviderRecord provider;
        try (Connection connection = dataSource.getConnection()) {
            model = modelRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "模型不存在或已删除"));
            provider = providerRepository.findLiveById(connection, model.providerId())
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "Provider不存在或已删除"));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测目标解析失败");
        }
        com.lightai.spi.check.ProviderCheckExecutor executor = modelCheckExecutors.stream()
                .filter(candidate -> candidate.supports(provider.type()))
                .findFirst()
                .orElseThrow(() -> new LightAiException(ErrorCode.PROVIDER_ADAPTER_NOT_FOUND,
                        "Provider 类型未加载对应 Adapter：" + provider.type()));
        com.lightai.spi.check.ProviderCheckExecutor.CheckOutcome outcome;
        try {
            outcome = executor.execute(new com.lightai.spi.check.ProviderCheckExecutor.CheckInvocation(
                    provider.type(), provider.baseUrl(), provider.proxyUrl(),
                    provider.connectTimeoutMs(), provider.readTimeoutMs(), provider.defaultHeaders(),
                    model.modelId(), null, command.resolvedMode(), command.resolvedTimeoutMs()));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            outcome = com.lightai.spi.check.ProviderCheckExecutor.CheckOutcome.failure(
                    (int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis(),
                    ErrorCode.INTERNAL_ERROR.name(), "检测执行失败");
        }
        OffsetDateTime endedAt = OffsetDateTime.now();
        int totalMs = outcome.totalMs() >= 0 ? outcome.totalMs()
                : (int) java.time.Duration.between(startedAt, endedAt).toMillis();
        com.lightai.storage.check.CheckRecordRow row = new com.lightai.storage.check.CheckRecordRow(
                UUID.randomUUID(), com.lightai.storage.check.CheckRecordRow.TARGET_PROVIDER_MODEL, id,
                command.resolvedMode(), outcome.succeeded() ? "SUCCEEDED" : "FAILED",
                context.authContext().userId(), outcome.traceId(), outcome.attemptId(),
                startedAt, endedAt, totalMs, outcome.usage(), outcome.providerRequestId(),
                outcome.errorCode(), outcome.errorSummary());
        try (Connection connection = dataSource.getConnection()) {
            modelCheckRecordRepository.insert(connection, row);
            modelRuntimeStateWriter.upsertProviderState(connection, model.providerId(),
                    outcome.succeeded() ? "AVAILABLE" : "UNAVAILABLE", endedAt,
                    outcome.errorCode(), outcome.errorSummary());
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "检测记录写入失败");
        }
        return new com.lightai.client.provider.ProviderCheckRecord(
                row.id().toString(), row.targetType(), row.targetId().toString(), row.mode(),
                row.status(), row.startedAt(), row.endedAt(), row.totalMs(), row.traceId(),
                row.attemptId(), row.usage(), row.errorCode(), row.errorSummary(),
                row.providerRequestId());
    }

    private void validateOperation(String operation) {
        if (!ImpactService.OPERATION_DISABLE.equals(operation)
                && !ImpactService.OPERATION_DELETE.equals(operation)) {
            throw fieldError("operation", "INVALID", "operation 仅支持 DISABLE/DELETE");
        }
    }

    private static long requireVersion(Long version) {
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "编辑操作必须提交正整数 version");
        }
        return version;
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        throw fieldError("enabled", "INVALID", "布尔值仅支持 true/false");
    }

    private static LightAiException fieldError(String field, String code, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "字段校验失败",
                List.of(new FieldIssue(field, code, message)));
    }
}
