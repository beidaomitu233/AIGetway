package com.lightai.admin.alias;

import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.draft.WriteContext;
import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.client.access.CandidateReorderCommand;
import com.lightai.client.access.ImpactAnalysis;
import com.lightai.client.access.ModelAliasCreateCommand;
import com.lightai.client.access.ModelAliasDetail;
import com.lightai.client.access.ModelAliasListItem;
import com.lightai.client.access.ModelAliasUpdateCommand;
import com.lightai.client.access.RouteCandidateDetail;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.storage.access.ConfigReferenceQuery;
import com.lightai.storage.alias.ModelAliasRecord;
import com.lightai.storage.alias.ModelAliasRepository;
import com.lightai.storage.alias.RouteCandidateRecord;
import com.lightai.storage.alias.RouteCandidateRepository;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.model.ProviderModelRecord;
import com.lightai.storage.model.ProviderModelRepository;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Model Alias 服务（BE-016/018）：别名草稿与候选原子重排。
 * alias 创建后不可变（2—64，字母数字点短横线下划线，全局唯一）；
 * 无候选允许保存草稿，发布校验拦截；重排要求完整集合、版本一致，任一冲突整批回滚。
 */
public class ModelAliasService {

    public static final String ENTITY_TYPE = "MODEL_ALIAS";
    public static final String CANDIDATE_ENTITY_TYPE = "ROUTE_CANDIDATE";
    public static final Set<String> SORTABLE = Set.of(
            "alias", "display_name", "enabled", "updated_at");
    public static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{2,64}$");

    private final DataSource dataSource;
    private final DraftWriteService draftWriteService;
    private final ModelAliasRepository aliasRepository;
    private final RouteCandidateRepository candidateRepository;
    private final ProviderModelRepository modelRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final ConfigReferenceQuery referenceQuery;
    private final ImpactService impactService;
    private final Clock clock;

    public ModelAliasService(DataSource dataSource, DraftWriteService draftWriteService,
                             ModelAliasRepository aliasRepository, RouteCandidateRepository candidateRepository,
                             ProviderModelRepository modelRepository, DraftChangeRepository draftChangeRepository,
                             ConfigReferenceQuery referenceQuery, ImpactService impactService, Clock clock) {
        this.dataSource = dataSource;
        this.draftWriteService = draftWriteService;
        this.aliasRepository = aliasRepository;
        this.candidateRepository = candidateRepository;
        this.modelRepository = modelRepository;
        this.draftChangeRepository = draftChangeRepository;
        this.referenceQuery = referenceQuery;
        this.impactService = impactService;
        this.clock = clock;
    }

    public PageResult<ModelAliasListItem> list(String keyword, Boolean enabled, Boolean supportStream,
                                               ListQuerySupport.ListQuery query) {
        try (Connection connection = dataSource.getConnection()) {
            StringBuilder filterSql = new StringBuilder();
            List<Object> filters = new ArrayList<>();
            if (enabled != null) {
                filterSql.append("enabled = ?");
                filters.add(enabled);
            }
            if (keyword != null && !keyword.isBlank()) {
                filterSql.append(filterSql.length() > 0 ? " AND " : "")
                        .append("(alias ILIKE ? OR display_name ILIKE ? OR description ILIKE ?)");
                String like = "%" + keyword.trim() + "%";
                filters.add(like);
                filters.add(like);
                filters.add(like);
            }
            String filter = filterSql.toString().trim();
            List<ModelAliasRecord> records = aliasRepository.list(
                    connection, filter, filters, query.sort(), query.offset(), query.limit());
            long total = aliasRepository.count(connection, filter, filters);

            List<UUID> ids = records.stream().map(ModelAliasRecord::id).toList();
            Set<UUID> draftChanged = draftChangeRepository.findExistingEntityIds(connection, ENTITY_TYPE, ids);
            Map<UUID, List<RouteCandidateRecord>> candidatesByAlias = new java.util.HashMap<>();
            for (UUID id : ids) {
                candidatesByAlias.put(id, candidateRepository.listByAlias(connection, id, "priority asc, id asc"));
            }
            // 流式能力摘要：取本页候选涉及的模型，一次性组装
            List<UUID> modelIds = candidatesByAlias.values().stream()
                    .flatMap(List::stream).map(RouteCandidateRecord::providerModelId).distinct().toList();
            Map<UUID, ProviderModelRecord> models = modelIds.isEmpty() ? Map.of()
                    : modelRepository.list(connection, "id IN (" + placeholders(modelIds.size()) + ")",
                    new ArrayList<Object>(modelIds), "id asc", 0, modelIds.size())
                    .stream().collect(Collectors.toMap(ProviderModelRecord::id, record -> record));

            OffsetDateTime now = OffsetDateTime.now(clock);
            List<ModelAliasListItem> items = records.stream()
                    .map(record -> {
                        List<RouteCandidateRecord> candidates = candidatesByAlias.getOrDefault(record.id(), List.of());
                        long streamCandidates = candidates.stream()
                                .filter(candidate -> candidate.enabled()
                                        && models.get(candidate.providerModelId()) != null
                                        && Boolean.TRUE.equals(models.get(candidate.providerModelId()).supportStream()))
                                .count();
                        boolean aliasSupportsStream = streamCandidates > 0
                                && streamCandidates == candidates.stream().filter(RouteCandidateRecord::enabled).count();
                        return new ModelAliasListItem(
                                record.id().toString(), record.alias(), record.displayName(),
                                record.routeStrategy(), candidates.size(), null,
                                supportStream == null ? null : aliasSupportsStream,
                                (int) streamCandidates, record.enabled(),
                                draftChanged.contains(record.id()), record.updatedAt());
                    })
                    .toList();
            return new PageResult<>(items, total, query.page(), query.pageSize(), query.sort(), now, now);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "Alias 列表读取失败");
        }
    }

    public ModelAliasDetail get(UUID id) {
        try (Connection connection = dataSource.getConnection()) {
            ModelAliasRecord record = load(connection, id);
            boolean draftChanged = draftChangeRepository.existsByEntity(connection, ENTITY_TYPE, id);
            return toDetail(record, candidateRepository.listByAlias(connection, id, "priority asc, id asc"),
                    draftChanged);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "Alias 详情读取失败");
        }
    }

    public ManagementOperationResult<ModelAliasDetail> create(ModelAliasCreateCommand command, WriteContext ctx) {
        String alias = requireAlias(command.alias());
        String displayName = requireText(command.displayName(), "display_name", 64);
        if (command.description() != null && command.description().length() > 500) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "description 最多 500 字符", "description");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        ModelAliasRecord record = new ModelAliasRecord(
                id, alias, displayName, command.description(), "PRIORITY_WEIGHTED",
                command.enabled() == null || command.enabled(), 1L, now, now, null);

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "CREATE", ENTITY_TYPE, id.toString(), 0L,
                null,
                connection -> {
                    if (aliasRepository.existsAliveByAlias(connection, alias)) {
                        throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "别名已存在", "alias");
                    }
                    aliasRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE, id, displayName, "CREATE", 1L, List.of(
                            FieldChange.changed("alias", null, alias),
                            FieldChange.changed("display_name", null, displayName),
                            FieldChange.changed("enabled", null, record.enabled())));
                }));
        return new ManagementOperationResult<>(id.toString(), result.entityVersion(), get(id),
                true, result.draftRevision(), ctx.requestId());
    }

    public ManagementOperationResult<ModelAliasDetail> update(UUID id, ModelAliasUpdateCommand command,
                                                              WriteContext ctx) {
        requireText(command.displayName(), "display_name", 64);
        if (command.description() != null && command.description().length() > 500) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "description 最多 500 字符", "description");
        }
        long newVersion = command.version() + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE, id.toString(), command.version(),
                connection -> aliasRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    ModelAliasRecord persisted = load(connection, id);
                    ModelAliasRecord updated = new ModelAliasRecord(
                            persisted.id(), persisted.alias(), command.displayName(), command.description(),
                            persisted.routeStrategy(),
                            command.enabled() == null ? persisted.enabled() : command.enabled(),
                            newVersion, persisted.createdAt(), OffsetDateTime.now(clock), null);
                    aliasRepository.update(connection, updated);
                    return new DraftEntityChange(ENTITY_TYPE, id, command.displayName(), "UPDATE", newVersion,
                            List.of(FieldChange.changed("display_name", persisted.displayName(), command.displayName()),
                                    FieldChange.changed("enabled", persisted.enabled(), updated.enabled())));
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, get(id),
                true, result.draftRevision(), ctx.requestId());
    }

    public ManagementOperationResult<ModelAliasDetail> changeEnabled(UUID id, boolean enable, long version,
                                                                     String confirmedImpactVersion,
                                                                     WriteContext ctx) {
        if (!enable && confirmedImpactVersion != null && !confirmedImpactVersion.isBlank()) {
            assertImpactConfirmed(id, confirmedImpactVersion);
        }
        long newVersion = version + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                enable ? "ENABLE" : "DISABLE", ENTITY_TYPE, id.toString(), version,
                connection -> aliasRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    ModelAliasRecord persisted = load(connection, id);
                    ModelAliasRecord updated = new ModelAliasRecord(
                            persisted.id(), persisted.alias(), persisted.displayName(), persisted.description(),
                            persisted.routeStrategy(), enable, newVersion, persisted.createdAt(),
                            OffsetDateTime.now(clock), null);
                    aliasRepository.update(connection, updated);
                    return new DraftEntityChange(ENTITY_TYPE, id, persisted.displayName(),
                            enable ? "ENABLE" : "DISABLE", newVersion, List.of(
                            FieldChange.changed("enabled", persisted.enabled(), enable)));
                }));
        return new ManagementOperationResult<>(id.toString(), newVersion, get(id),
                true, result.draftRevision(), ctx.requestId());
    }

    public ManagementOperationResult<ModelAliasDetail> delete(UUID id, long version,
                                                              String confirmedImpactVersion, WriteContext ctx) {
        assertImpactConfirmed(id, confirmedImpactVersion);
        long newVersion = version + 1;
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "DELETE", ENTITY_TYPE, id.toString(), version,
                connection -> aliasRepository.findAliveVersion(connection, id).orElse(null),
                connection -> {
                    ModelAliasRecord persisted = load(connection, id);
                    OffsetDateTime now = OffsetDateTime.now(clock);
                    aliasRepository.update(connection, new ModelAliasRecord(
                            persisted.id(), persisted.alias(), persisted.displayName(), persisted.description(),
                            persisted.routeStrategy(), persisted.enabled(), newVersion, persisted.createdAt(),
                            now, now));
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

    /** 候选列表：按 priority 升序、同优先级按 id，能力字段由模型组合。 */
    public List<RouteCandidateDetail> candidates(UUID aliasId) {
        try (Connection connection = dataSource.getConnection()) {
            load(connection, aliasId);
            List<RouteCandidateRecord> records = candidateRepository.listByAlias(
                    connection, aliasId, "priority asc, id asc");
            return toCandidateDetails(connection, records);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "候选列表读取失败");
        }
    }

    /** 候选原子重排（BE-018）：完整集合、无重复、版本全一致后统一写入；weight 保持原值。 */
    public List<RouteCandidateDetail> reorder(UUID aliasId, CandidateReorderCommand command, WriteContext ctx) {
        if (command.items() == null || command.items().isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "items 非空数组", "items");
        }
        List<CandidateReorderCommand.Item> items = command.items();
        Set<String> ids = new HashSet<>();
        for (CandidateReorderCommand.Item item : items) {
            if (item.id() == null || !ids.add(item.id())) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "items 包含缺失或重复 ID", "items");
            }
            if (item.priority() == null || item.priority() < 1 || item.priority() > 100) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "priority 范围 1—100", "items");
            }
        }
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                ctx.requestId(), ctx.operatorId(), ctx.sourceMode(), ctx.sourceIpMasked(),
                "UPDATE", CANDIDATE_ENTITY_TYPE, aliasId.toString(), 0L,
                null,
                connection -> {
                    load(connection, aliasId);
                    List<RouteCandidateRecord> existing = candidateRepository.listByAlias(
                            connection, aliasId, "priority asc, id asc");
                    Set<String> existingIds = existing.stream()
                            .map(record -> record.id().toString()).collect(Collectors.toSet());
                    if (!existingIds.equals(ids)) {
                        throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                                "必须提交完整候选集合", "items");
                    }
                    // 全部版本核对通过后统一写入；任一冲突抛出使整批回滚
                    for (CandidateReorderCommand.Item item : items) {
                        UUID candidateId = UUID.fromString(item.id());
                        RouteCandidateRecord record = candidateRepository.find(connection, candidateId)
                                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "候选不存在"));
                        if (record.version() != item.version()) {
                            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "候选版本已变化，整批不提交",
                                    null, ctx.requestId(), null, record.version(), null, null);
                        }
                    }
                    long baseRevision = 0L;
                    for (CandidateReorderCommand.Item item : items) {
                        candidateRepository.updatePriority(connection, UUID.fromString(item.id()),
                                item.priority(), item.version() + 1);
                    }
                    return new DraftEntityChange(CANDIDATE_ENTITY_TYPE, aliasId, "候选重排", "UPDATE",
                            baseRevision + 1, items.stream()
                            .map(item -> FieldChange.changed("candidates." + item.id() + ".priority",
                                    null, item.priority()))
                            .toList());
                }));
        return candidates(aliasId);
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

    private ModelAliasRecord load(Connection connection, UUID id) {
        return aliasRepository.find(connection, id)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "别名不存在或已删除"));
    }

    private List<RouteCandidateDetail> toCandidateDetails(Connection connection,
                                                          List<RouteCandidateRecord> records) {
        List<UUID> modelIds = records.stream().map(RouteCandidateRecord::providerModelId).distinct().toList();
        Map<UUID, ProviderModelRecord> models = modelRepository.list(connection,
                "id IN (" + placeholders(modelIds.size()) + ")", new ArrayList<Object>(modelIds),
                "id asc", 0, Math.max(modelIds.size(), 1))
                .stream().collect(Collectors.toMap(ProviderModelRecord::id, record -> record));
        Map<UUID, String> providerNames = new java.util.HashMap<>();
        Map<UUID, String> poolNames = new java.util.HashMap<>();
        for (ProviderModelRecord model : models.values()) {
            referenceQuery.findProviderSummary(connection, model.providerId())
                    .ifPresent(summary -> providerNames.put(model.providerId(), summary.name()));
        }
        for (UUID poolId : records.stream().map(RouteCandidateRecord::credentialPoolId).distinct().toList()) {
            referenceQuery.findPool(connection, poolId)
                    .ifPresent(summary -> poolNames.put(poolId, summary.name()));
        }
        return records.stream().map(record -> {
            ProviderModelRecord model = models.get(record.providerModelId());
            return new RouteCandidateDetail(
                    record.id().toString(), record.aliasId().toString(),
                    record.providerModelId().toString(),
                    model == null ? null : providerNames.get(model.providerId()),
                    model == null ? null : model.displayName(),
                    model == null ? null : model.modelId(),
                    record.credentialPoolId().toString(),
                    poolNames.get(record.credentialPoolId()),
                    record.priority(), record.weight(), record.enabled(),
                    model == null ? null : model.supportStream(),
                    model == null ? null : model.supportSystemMessage(),
                    model == null ? null : model.contextWindow(),
                    true, record.createdAt(), record.updatedAt(), record.version());
        }).toList();
    }

    private static ModelAliasDetail toDetail(ModelAliasRecord record, List<RouteCandidateRecord> candidates,
                                             boolean draftChanged) {
        return new ModelAliasDetail(record.id().toString(), record.alias(), record.displayName(),
                record.description(), record.routeStrategy(), record.enabled(),
                candidates.size(), (int) candidates.stream().filter(RouteCandidateRecord::enabled).count(),
                draftChanged, record.createdAt(), record.updatedAt(), record.version());
    }

    private static String requireAlias(String alias) {
        if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "alias 2—64，仅字母数字点短横线下划线", "alias");
        }
        return alias;
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.trim().length() < 2 || value.trim().length() > max) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, field + " 长度 2—" + max, field);
        }
        return value.trim();
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(i == 0 ? "?" : ",?");
        }
        return builder.toString();
    }
}
