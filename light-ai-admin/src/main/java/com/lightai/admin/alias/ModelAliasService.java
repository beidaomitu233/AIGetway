package com.lightai.admin.alias;

import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.alias.ModelAliasDetail;
import com.lightai.client.alias.ModelAliasSaveCommand;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ImpactConfirmCommand;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.storage.alias.AliasRecord;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.alias.JdbcCandidateRepository;
import com.lightai.storage.draft.DraftChangeRepository;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Model Alias 管理服务（BE-016）。
 * alias 全局唯一且创建后只读；无候选可保存草稿，启用/发布要求至少一候选；
 * 删除被候选引用时拒绝（OBJECT_IN_USE）；数据范围由 application_scope 限制（BE-P06 联调）。
 */
public class ModelAliasService {

    public static final String ENTITY_TYPE = "MODEL_ALIAS";
    private static final Set<String> SORTABLE = Set.of("alias", "updated_at", "created_at");

    private final DataSource dataSource;
    private final JdbcAliasRepository aliasRepository;
    private final JdbcCandidateRepository candidateRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final DraftWriteService draftWriteService;
    private final ImpactService impactService;
    private final PageResultFactory pageResultFactory;
    private final String sourceMode;

    public ModelAliasService(DataSource dataSource, JdbcAliasRepository aliasRepository,
                             JdbcCandidateRepository candidateRepository,
                             DraftChangeRepository draftChangeRepository,
                             DraftWriteService draftWriteService, ImpactService impactService,
                             PageResultFactory pageResultFactory, String sourceMode) {
        this.dataSource = dataSource;
        this.aliasRepository = aliasRepository;
        this.candidateRepository = candidateRepository;
        this.draftChangeRepository = draftChangeRepository;
        this.draftWriteService = draftWriteService;
        this.impactService = impactService;
        this.pageResultFactory = pageResultFactory;
        this.sourceMode = sourceMode;
    }

    public PageResult<ModelAliasDetail> list(RequestContext context, Map<String, String> params) {
        RequestPermissions.require(context, Permissions.ALIAS_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(
                params.get("page"), params.get("page_size"), params.get("sort"), SORTABLE, "alias asc");
        try (Connection connection = dataSource.getConnection()) {
            List<AliasRecord> records = aliasRepository.list(connection, params.get("keyword"),
                    parseBoolean(params.get("enabled")), query.sort(), query.limit(),
                    (int) query.offset());
            long total = aliasRepository.count(connection, params.get("keyword"),
                    parseBoolean(params.get("enabled")));
            List<ModelAliasDetail> items = new ArrayList<>(records.size());
            for (AliasRecord record : records) {
                items.add(toDetail(connection, record));
            }
            return pageResultFactory.create(items, total, query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "别名列表当前无法读取");
        }
    }

    public ModelAliasDetail detail(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.ALIAS_VIEW);
        UUID id = parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            AliasRecord record = aliasRepository.findLiveById(connection, id)
                    .orElseThrow(() -> notFound());
            return toDetail(connection, record);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "别名详情当前无法读取");
        }
    }

    public ManagementOperationResult<ModelAliasDetail> create(RequestContext context,
                                                              ModelAliasSaveCommand command) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID id = UUID.randomUUID();
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "CREATE", ENTITY_TYPE.toLowerCase(), null, 0, null,
                connection -> {
                    requireValidAlias(command);
                    if (aliasRepository.existsByLiveAlias(connection, command.alias())) {
                        throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "别名已存在",
                                List.of(new FieldIssue("alias", "DUPLICATED", "别名全局唯一")));
                    }
                    AliasRecord record = new AliasRecord(id, command.alias(),
                            command.displayName().strip(), command.description(), "PRIORITY_WEIGHTED",
                            command.enabled(), 1L, OffsetDateTime.now(), OffsetDateTime.now());
                    aliasRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, command.alias(),
                            "CREATE", 1L, List.of(FieldChange.changed("alias", null, command.alias())));
                }));

        try (Connection connection = dataSource.getConnection()) {
            AliasRecord record = aliasRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "创建结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "创建结果读取失败");
        }
    }

    public ManagementOperationResult<ModelAliasDetail> update(RequestContext context, String rawId,
                                                              ModelAliasSaveCommand command) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID id = parseId(rawId);
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE.toLowerCase(), id.toString(), requireVersion(command.version()),
                connection -> aliasRepository.lockLiveById(connection, id)
                        .map(AliasRecord::version).orElse(null),
                connection -> {
                    AliasRecord current = aliasRepository.lockLiveById(connection, id)
                            .orElseThrow(this::notFound);
                    AliasRecord saved = aliasRepository.update(connection, new AliasRecord(
                            current.id(), current.alias(), command.displayName().strip(),
                            command.description(), current.routeStrategy(), command.enabled(),
                            current.version(), current.createdAt(), current.updatedAt()));
                    List<FieldChange> changes = new ArrayList<>();
                    if (!current.displayName().equals(command.displayName())) {
                        changes.add(FieldChange.changed("display_name", current.displayName(),
                                command.displayName()));
                    }
                    if (current.enabled() != command.enabled()) {
                        changes.add(FieldChange.changed("enabled", current.enabled(), command.enabled()));
                    }
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.alias(),
                            "UPDATE", saved.version(), List.copyOf(changes));
                }));

        try (Connection connection = dataSource.getConnection()) {
            AliasRecord record = aliasRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "更新结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "更新结果读取失败");
        }
    }

    public ManagementOperationResult<ModelAliasDetail> setEnabled(RequestContext context, String rawId,
                                                                  boolean enabled, Long version,
                                                                  String confirmedImpactVersion) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID id = parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        try (Connection connection = dataSource.getConnection()) {
            aliasRepository.findLiveById(connection, id).orElseThrow(this::notFound);
            if (enabled && candidateRepository.countLiveByAlias(connection, id) == 0) {
                throw fieldError("enabled", "NO_CANDIDATE", "启用别名至少需要一条候选");
            }
            if (!enabled) {
                impactService.verifyConfirmedImpact(confirmedImpactVersion,
                        impactOf(connection, id, currentAlias(connection, id).alias()));
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "别名状态校验失败");
        }

        draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                enabled ? "ENABLE" : "DISABLE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> aliasRepository.lockLiveById(connection, id)
                        .map(AliasRecord::version).orElse(null),
                connection -> {
                    AliasRecord current = aliasRepository.lockLiveById(connection, id)
                            .orElseThrow(this::notFound);
                    AliasRecord saved = aliasRepository.update(connection, new AliasRecord(
                            current.id(), current.alias(), current.displayName(),
                            current.description(), current.routeStrategy(), enabled,
                            current.version(), current.createdAt(), current.updatedAt()));
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.alias(),
                            enabled ? "ENABLE" : "DISABLE", saved.version(),
                            List.of(FieldChange.changed("enabled", current.enabled(), enabled)));
                }));

        try (Connection connection = dataSource.getConnection()) {
            AliasRecord record = aliasRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "操作结果读取失败"));
            long revision = draftWriteService == null ? -1 : -1;
            return new ManagementOperationResult<>(id.toString(), record.version(),
                    toDetail(connection, record), true, revision, requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "操作结果读取失败");
        }
    }

    public ManagementOperationResult<ModelAliasDetail> delete(RequestContext context, String rawId,
                                                              Long version,
                                                              String confirmedImpactVersion) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID id = parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        try (Connection connection = dataSource.getConnection()) {
            AliasRecord record = aliasRepository.findLiveById(connection, id).orElseThrow(this::notFound);
            impactService.verifyConfirmedImpact(confirmedImpactVersion,
                    impactOf(connection, id, record.alias()));
            if (candidateRepository.countLiveByAlias(connection, id) > 0) {
                throw new LightAiException(ErrorCode.OBJECT_IN_USE, "别名仍被候选引用，不能删除");
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "别名状态校验失败");
        }

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "DELETE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> aliasRepository.lockLiveById(connection, id)
                        .map(AliasRecord::version).orElse(null),
                connection -> {
                    AliasRecord current = aliasRepository.lockLiveById(connection, id)
                            .orElseThrow(this::notFound);
                    aliasRepository.markDeleted(connection, id);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.alias(),
                            "DELETE", current.version(), List.of());
                }));

        return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                null, true, result.draftRevision(), requestId);
    }

    public com.lightai.client.impact.ImpactAnalysis impact(RequestContext context, String rawId,
                                                           String operation) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        if (!ImpactService.OPERATION_DISABLE.equals(operation)
                && !ImpactService.OPERATION_DELETE.equals(operation)) {
            throw fieldError("operation", "INVALID", "operation 仅支持 DISABLE/DELETE");
        }
        UUID id = parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            AliasRecord record = aliasRepository.findLiveById(connection, id).orElseThrow(this::notFound);
            return impactOf(connection, id, record.alias());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响分析当前无法读取");
        }
    }

    private com.lightai.client.impact.ImpactAnalysis impactOf(Connection connection, UUID id,
                                                              String alias) {
        // Alias 的引用：其候选集合（禁用/删除影响候选与调用路径）
        var candidates = candidateRepository.listLiveByAlias(connection, id);
        List<com.lightai.client.impact.ImpactReference> references = new ArrayList<>();
        for (var candidate : candidates) {
            references.add(new com.lightai.client.impact.ImpactReference("route_candidate",
                    candidate.id().toString(), "candidate:" + candidate.id(), "ALIAS_CANDIDATE"));
        }
        boolean canDelete = candidates.isEmpty();
        return new com.lightai.client.impact.ImpactAnalysis(
                ImpactService.computeVersion(ENTITY_TYPE.toLowerCase(), id, references, List.of()),
                ENTITY_TYPE.toLowerCase(), id.toString(), references, List.of(),
                canDelete, canDelete ? List.of()
                : references.stream().map(reference ->
                        reference.entityType() + ":" + reference.name()).toList());
    }

    private void requireValidAlias(ModelAliasSaveCommand command) {
        if (command.alias() == null) {
            throw fieldError("alias", "REQUIRED", "创建必须提供 alias");
        }
    }

    private ModelAliasDetail toDetail(Connection connection, AliasRecord record) {
        long candidateCount = candidateRepository.countLiveByAlias(connection, record.id());
        String updatedBy = draftChangeRepository
                .findLatestModifier(connection, ENTITY_TYPE, record.id()).orElse("");
        return new ModelAliasDetail(
                record.id().toString(), record.alias(), record.displayName(), record.description(),
                record.routeStrategy(), candidateCount, candidateCount, candidateCount, 0,
                record.enabled(),
                draftChangeRepository.findChangedEntityIds(connection, ENTITY_TYPE,
                        List.of(record.id())).contains(record.id()),
                record.updatedAt(), record.version(), null, updatedBy, null, null);
    }

    private AliasRecord currentAlias(Connection connection, UUID id) {
        return aliasRepository.findLiveById(connection, id).orElseThrow(this::notFound);
    }

    private LightAiException notFound() {
        return new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "别名不存在或已删除");
    }

    public static UUID parseId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在或已删除");
        }
        try {
            return UUID.fromString(rawId.strip());
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在或已删除");
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
