package com.lightai.admin.alias;

import com.lightai.admin.check.ProviderCheckService;
import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.provider.ProviderService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.alias.ReorderCommand;
import com.lightai.client.alias.RouteCandidateDetail;
import com.lightai.client.alias.RouteCandidateSaveCommand;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.model.ProviderModelDetail;
import com.lightai.client.model.ProviderModelSaveCommand;
import com.lightai.client.protocol.Permissions;
import com.lightai.storage.alias.AliasRecord;
import com.lightai.storage.alias.CandidateRecord;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.alias.JdbcCandidateRepository;
import com.lightai.storage.model.JdbcProviderModelRepository;
import com.lightai.storage.model.ProviderModelRecord;
import com.lightai.storage.pool.JdbcPoolRepository;
import com.lightai.storage.pool.PoolRecord;
import com.lightai.storage.provider.JdbcProviderRepository;
import com.lightai.storage.provider.ProviderRecord;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 候选管理服务（BE-017/018）。
 * 同 Provider 约束在保存阶段校验（发布阶段由快照校验再次拦截）；
 * (alias, model, pool) 三元组重复返回 DUPLICATE_ROUTE_CANDIDATE；
 * 更新不换 model；重排要求完整集合且全部 version 核对后统一写入。
 */
public class RouteCandidateService {

    public static final String ENTITY_TYPE = "ROUTE_CANDIDATE";

    private final DataSource dataSource;
    private final JdbcCandidateRepository candidateRepository;
    private final JdbcAliasRepository aliasRepository;
    private final JdbcProviderModelRepository modelRepository;
    private final JdbcPoolRepository poolRepository;
    private final JdbcProviderRepository providerRepository;
    private final DraftWriteService draftWriteService;
    private final ProviderCheckService providerCheckService;
    private final String sourceMode;

    public RouteCandidateService(DataSource dataSource, JdbcCandidateRepository candidateRepository,
                                 JdbcAliasRepository aliasRepository,
                                 JdbcProviderModelRepository modelRepository,
                                 JdbcPoolRepository poolRepository,
                                 JdbcProviderRepository providerRepository,
                                 DraftWriteService draftWriteService,
                                 ProviderCheckService providerCheckService, String sourceMode) {
        this.dataSource = dataSource;
        this.candidateRepository = candidateRepository;
        this.aliasRepository = aliasRepository;
        this.modelRepository = modelRepository;
        this.poolRepository = poolRepository;
        this.providerRepository = providerRepository;
        this.draftWriteService = draftWriteService;
        this.providerCheckService = providerCheckService;
        this.sourceMode = sourceMode;
    }

    /** 候选集合（GET /admin/model-aliases/{id}/candidates，无分页）。 */
    public List<RouteCandidateDetail> candidates(RequestContext context, String rawAliasId) {
        RequestPermissions.require(context, Permissions.ALIAS_VIEW);
        UUID aliasId = parseId(rawAliasId);
        try (Connection connection = dataSource.getConnection()) {
            aliasRepository.findLiveById(connection, aliasId).orElseThrow(this::notFound);
            List<CandidateRecord> records = candidateRepository.listLiveByAlias(connection, aliasId);
            List<RouteCandidateDetail> details = new ArrayList<>(records.size());
            for (CandidateRecord record : records) {
                details.add(toDetail(connection, record));
            }
            return List.copyOf(details);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "候选列表当前无法读取");
        }
    }

    public ManagementOperationResult<RouteCandidateDetail> create(RequestContext context,
                                                                  String rawAliasId,
                                                                  RouteCandidateSaveCommand command) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID aliasId = parseId(rawAliasId);
        command.validateForCreate();
        UUID id = UUID.randomUUID();
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "CREATE", ENTITY_TYPE.toLowerCase(), aliasId.toString(), 0, null,
                connection -> {
                    aliasRepository.findLiveById(connection, aliasId).orElseThrow(this::notFound);
                    ProviderModelRecord model = modelRepository.findLiveById(connection,
                            command.providerModelId())
                            .orElseThrow(() -> referenceInvalid("provider_model_id"));
                    PoolRecord pool = poolRepository.findLiveById(connection, command.credentialPoolId())
                            .orElseThrow(() -> referenceInvalid("credential_pool_id"));
                    // 同 Provider 约束：保存阶段拒绝（发布阶段由快照校验再拦截）
                    if (!model.providerId().equals(pool.providerId())) {
                        throw new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                "模型与凭证池必须属于同一 Provider");
                    }
                    if (candidateRepository.existsTriple(connection, aliasId,
                            command.providerModelId(), command.credentialPoolId())) {
                        throw new LightAiException(ErrorCode.DUPLICATE_ROUTE_CANDIDATE,
                                "该 Alias 下已存在相同模型与凭证池组合");
                    }
                    CandidateRecord record = new CandidateRecord(id, aliasId,
                            command.providerModelId(), command.credentialPoolId(),
                            command.priority(), command.weight(), command.enabled(), 1L,
                            OffsetDateTime.now(), OffsetDateTime.now());
                    candidateRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, "candidate",
                            "CREATE", 1L, List.of(FieldChange.changed("provider_model_id",
                                    null, command.providerModelId().toString())));
                }));

        try (Connection connection = dataSource.getConnection()) {
            CandidateRecord record = candidateRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "创建结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "创建结果读取失败");
        }
    }

    /** 候选更新：model/pool 不可变，仅 priority/weight/enabled。 */
    public ManagementOperationResult<RouteCandidateDetail> update(RequestContext context, String rawId,
                                                                  RouteCandidateSaveCommand command) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID id = parseId(rawId);
        command.validatePriorityWeight();
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE.toLowerCase(), id.toString(), requireVersion(command.version()),
                connection -> candidateRepository.lockLiveById(connection, id)
                        .map(CandidateRecord::version).orElse(null),
                connection -> {
                    CandidateRecord current = candidateRepository.lockLiveById(connection, id)
                            .orElseThrow(this::notFound);
                    CandidateRecord saved = candidateRepository.update(connection, new CandidateRecord(
                            current.id(), current.aliasId(), current.providerModelId(),
                            current.credentialPoolId(), command.priority(), command.weight(),
                            command.enabled(), current.version(), current.createdAt(),
                            current.updatedAt()));
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, "candidate",
                            "UPDATE", saved.version(),
                            List.of(FieldChange.changed("priority", current.priority(),
                                    command.priority())));
                }));

        try (Connection connection = dataSource.getConnection()) {
            CandidateRecord record = candidateRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "更新结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "更新结果读取失败");
        }
    }

    public ManagementOperationResult<RouteCandidateDetail> delete(RequestContext context, String rawId,
                                                                  Long version) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID id = parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "DELETE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> candidateRepository.lockLiveById(connection, id)
                        .map(CandidateRecord::version).orElse(null),
                connection -> {
                    CandidateRecord current = candidateRepository.lockLiveById(connection, id)
                            .orElseThrow(this::notFound);
                    candidateRepository.markDeleted(connection, id);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, "candidate",
                            "DELETE", current.version(), List.of());
                }));

        return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                null, true, result.draftRevision(), requestId);
    }

    /**
     * 原子重排（BE-018）：完整候选集合、无重复 id、逐项 version 核对，
     * 全部通过后统一写入；任一冲突整体回滚。
     */
    public List<RouteCandidateDetail> reorder(RequestContext context, String rawAliasId,
                                              ReorderCommand command) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID aliasId = parseId(rawAliasId);
        command.validate();
        String requestId = context.requestId();

        draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE.toLowerCase(), aliasId.toString(), 0, null,
                connection -> {
                    aliasRepository.findLiveById(connection, aliasId).orElseThrow(this::notFound);
                    List<CandidateRecord> live = candidateRepository.listLiveByAlias(connection, aliasId);
                    Set<UUID> liveIds = new HashSet<>();
                    live.forEach(candidate -> liveIds.add(candidate.id()));

                    Set<UUID> seen = new HashSet<>();
                    for (ReorderCommand.ReorderItem item : command.items()) {
                        UUID itemId = parseId(item.id());
                        if (!seen.add(itemId)) {
                            throw fieldError("items", "DUPLICATED", "items 存在重复候选 id");
                        }
                        // 完整集合校验：不允许只提交部分候选
                        if (!liveIds.contains(itemId)) {
                            throw fieldError("items", "UNKNOWN_CANDIDATE", "候选不属于该别名");
                        }
                    }
                    if (seen.size() != liveIds.size()) {
                        throw fieldError("items", "INCOMPLETE", "必须提供当前别名下完整候选集合");
                    }
                    // 全部 version 核对后统一写入
                    for (ReorderCommand.ReorderItem item : command.items()) {
                        UUID itemId = parseId(item.id());
                        CandidateRecord current = candidateRepository.lockLiveById(connection, itemId)
                                .orElseThrow(this::notFound);
                        if (current.version() != item.version()) {
                            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                                    "候选版本已变化，请刷新后重试", null, requestId,
                                    null, current.version(), null, null);
                        }
                        CandidateRecord saved = candidateRepository.update(connection,
                                new CandidateRecord(current.id(), current.aliasId(),
                                        current.providerModelId(), current.credentialPoolId(),
                                        item.priority(), current.weight(), current.enabled(),
                                        current.version(), current.createdAt(), current.updatedAt()));
                        // 占位使用，实际写入以上一条 UPDATE 为准
                    }
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), aliasId, "candidates",
                            "UPDATE", 0L, List.of());
                }));

        try (Connection connection = dataSource.getConnection()) {
            List<RouteCandidateDetail> details = new ArrayList<>();
            for (CandidateRecord record : candidateRepository.listLiveByAlias(connection, aliasId)) {
                details.add(toDetail(connection, record));
            }
            return List.copyOf(details);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "重排结果读取失败");
        }
    }

    /** 候选探测：复用检测编排，目标锁定候选的模型与池。 */
    public com.lightai.client.provider.ProviderCheckRecord probe(RequestContext context, String rawId,
                                                                 com.lightai.client.provider.ProviderCheckCommand command) {
        RequestPermissions.require(context, Permissions.PROVIDER_CHECK);
        UUID id = parseId(rawId);
        CandidateRecord candidate;
        try (Connection connection = dataSource.getConnection()) {
            candidate = candidateRepository.findLiveById(connection, id)
                    .orElseThrow(this::notFound);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "候选读取失败");
        }
        // 探测目标固定为候选的模型与池凭证
        ProviderModelRecord model;
        try (Connection connection = dataSource.getConnection()) {
            model = modelRepository.findLiveById(connection, candidate.providerModelId())
                    .orElseThrow(() -> referenceInvalid("provider_model_id"));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "候选模型读取失败");
        }
        return providerCheckService.check(context, model.providerId().toString(),
                new com.lightai.client.provider.ProviderCheckCommand(
                        null, model.id().toString(), candidate.credentialPoolId().toString(),
                        command.resolvedMode(), command.resolvedTimeoutMs()));
    }

    private RouteCandidateDetail toDetail(Connection connection, CandidateRecord record) {
        ProviderModelRecord model = modelRepository.findLiveById(connection,
                record.providerModelId()).orElse(null);
        PoolRecord pool = poolRepository.findLiveById(connection, record.credentialPoolId()).orElse(null);
        String providerName = model == null ? "" : providerRepository
                .findLiveById(connection, model.providerId())
                .map(ProviderRecord::name).orElse("");
        String runtimeStatus;
        String excludedReason = null;
        if (!record.enabled()) {
            runtimeStatus = RouteCandidateDetail.STATUS_DISABLED;
            excludedReason = "候选已停用";
        } else if (model == null || pool == null) {
            runtimeStatus = RouteCandidateDetail.STATUS_UNAVAILABLE;
            excludedReason = "引用的模型或凭证池不可用";
        } else if (!model.enabled() || !pool.enabled()) {
            runtimeStatus = RouteCandidateDetail.STATUS_UNAVAILABLE;
            excludedReason = "模型或凭证池已停用";
        } else {
            runtimeStatus = RouteCandidateDetail.STATUS_AVAILABLE;
        }
        return new RouteCandidateDetail(
                record.id().toString(), record.aliasId().toString(),
                model == null ? null : model.providerId().toString(), providerName,
                record.providerModelId().toString(),
                model == null ? "" : model.displayName(),
                model == null ? "" : model.modelId(),
                record.credentialPoolId().toString(),
                pool == null ? "" : pool.name(),
                record.priority(), record.weight(), record.enabled(),
                model == null ? null : model.supportStream(),
                model == null ? null : model.supportSystemMessage(),
                model == null ? null : model.contextWindow(),
                0, runtimeStatus, excludedReason,
                draftChangeRepositoryOf(connection, record.id()), record.version(),
                record.updatedAt());
    }

    private boolean draftChangeRepositoryOf(Connection connection, UUID id) {
        try (var statement = connection.prepareStatement("SELECT 1 FROM "
                + com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME
                + ".draft_change WHERE entity_type = 'route_candidate' AND entity_id = ?")) {
            statement.setObject(1, id);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private LightAiException notFound() {
        return new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "候选不存在或已删除");
    }

    private static LightAiException referenceInvalid(String field) {
        return new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID, "引用不合法",
                List.of(new FieldIssue(field, "INVALID", "引用对象不存在或关系不一致")));
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

    private static LightAiException fieldError(String field, String code, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "字段校验失败",
                List.of(new FieldIssue(field, code, message)));
    }
}
