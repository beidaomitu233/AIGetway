package com.lightai.admin.alias;

import com.lightai.admin.check.ChannelCheckService;
import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.channel.ChannelService;
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
import com.lightai.client.upstream.UpstreamModelDetail;
import com.lightai.client.upstream.UpstreamModelSaveCommand;
import com.lightai.client.protocol.Permissions;
import com.lightai.storage.alias.AliasRecord;
import com.lightai.storage.alias.CandidateRecord;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.alias.JdbcCandidateRepository;
import com.lightai.storage.upstream.JdbcUpstreamModelRepository;
import com.lightai.storage.upstream.UpstreamModelRecord;
import com.lightai.storage.channel.JdbcChannelRepository;
import com.lightai.storage.channel.ChannelRecord;
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
    private final JdbcUpstreamModelRepository modelRepository;
    private final JdbcChannelRepository channelRepository;
    private final DraftWriteService draftWriteService;
    private final ChannelCheckService providerCheckService;
    private final String sourceMode;

    public RouteCandidateService(DataSource dataSource, JdbcCandidateRepository candidateRepository,
                                 JdbcAliasRepository aliasRepository,
                                 JdbcUpstreamModelRepository modelRepository,
                                 JdbcChannelRepository channelRepository,
                                 DraftWriteService draftWriteService,
                                 ChannelCheckService providerCheckService, String sourceMode) {
        this.dataSource = dataSource;
        this.candidateRepository = candidateRepository;
        this.aliasRepository = aliasRepository;
        this.modelRepository = modelRepository;
        this.channelRepository = channelRepository;
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
        try { command.validateForCreate(); } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "路由参数不合法");
        }
        UUID id = UUID.randomUUID();
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "CREATE", ENTITY_TYPE.toLowerCase(), aliasId.toString(), 0, null,
                connection -> {
                    aliasRepository.findLiveById(connection, aliasId).orElseThrow(this::notFound);
                    UpstreamModelRecord model = modelRepository.findLiveById(connection,
                            command.upstreamModelId())
                            .orElseThrow(() -> referenceInvalid("upstream_model_id"));
                    ChannelRecord channel = channelRepository.findLiveById(connection, command.channelId())
                            .orElseThrow(() -> referenceInvalid("channel_id"));
                    // 归属约束：上游模型必须属于该渠道（发布阶段由快照校验再拦截）
                    if (!model.channelId().equals(channel.id())) {
                        throw new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                                "上游模型必须属于所选渠道");
                    }
                    if (candidateRepository.existsTriple(connection, aliasId,
                            command.upstreamModelId(), command.channelId())) {
                        throw new LightAiException(ErrorCode.DUPLICATE_ROUTE_CANDIDATE,
                                "该 Alias 下已存在相同模型与凭证池组合");
                    }
                    CandidateRecord record = new CandidateRecord(id, aliasId,
                            command.upstreamModelId(), command.channelId(),
                            command.priority(), command.weight(), command.enabled(), 1L,
                            OffsetDateTime.now(), OffsetDateTime.now());
                    candidateRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, "candidate",
                            "CREATE", 1L, List.of(FieldChange.changed("upstream_model_id",
                                    null, command.upstreamModelId().toString())));
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
    public ManagementOperationResult<RouteCandidateDetail> update(RequestContext context, String rawAliasId, String rawId,
                                                                  RouteCandidateSaveCommand command) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID id = parseId(rawId);
        UUID parentId = parseId(rawAliasId);
        try { command.validatePriorityWeight(); } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "路由参数不合法");
        }
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE.toLowerCase(), id.toString(), requireVersion(command.version()),
                connection -> candidateRepository.lockLiveById(connection, id)
                        .map(record -> requireInModel(connection, parentId, record).version()).orElse(null),
                connection -> {
                    CandidateRecord current = requireInModel(connection, parentId,
                            candidateRepository.lockLiveById(connection, id).orElseThrow(this::notFound));
                    if ((command.channelId() != null && !command.channelId().equals(current.channelId()))
                            || (command.upstreamModelId() != null
                            && !command.upstreamModelId().equals(current.upstreamModelId()))) {
                        throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE, "路由渠道和上游模型不可修改");
                    }
                    CandidateRecord saved = candidateRepository.update(connection, new CandidateRecord(
                            current.id(), current.aliasId(), current.upstreamModelId(),
                            current.channelId(), command.priority(), command.weight(),
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

    public ManagementOperationResult<RouteCandidateDetail> delete(RequestContext context, String rawAliasId, String rawId,
                                                                  Long version) {
        RequestPermissions.require(context, Permissions.ALIAS_MANAGE);
        UUID id = parseId(rawId);
        UUID parentId = parseId(rawAliasId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "DELETE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> candidateRepository.lockLiveById(connection, id)
                        .map(record -> requireInModel(connection, parentId, record).version()).orElse(null),
                connection -> {
                    CandidateRecord current = requireInModel(connection, parentId,
                            candidateRepository.lockLiveById(connection, id).orElseThrow(this::notFound));
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
        try { command.validate(); } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "路由参数不合法");
        }
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
                                        current.upstreamModelId(), current.channelId(),
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
    public com.lightai.client.channel.ChannelCheckRecord probe(RequestContext context, String rawAliasId, String rawId,
                                                                 com.lightai.client.channel.ChannelCheckCommand command) {
        RequestPermissions.require(context, Permissions.PROVIDER_CHECK);
        UUID id = parseId(rawId);
        UUID parentId = parseId(rawAliasId);
        CandidateRecord candidate;
        try (Connection connection = dataSource.getConnection()) {
            candidate = requireInModel(connection, parentId,
                    candidateRepository.findLiveById(connection, id).orElseThrow(this::notFound));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "候选读取失败");
        }
        // 探测目标固定为候选的模型与池凭证
        UpstreamModelRecord model;
        try (Connection connection = dataSource.getConnection()) {
            model = modelRepository.findLiveById(connection, candidate.upstreamModelId())
                    .orElseThrow(() -> referenceInvalid("upstream_model_id"));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "候选模型读取失败");
        }
        return providerCheckService.check(context, model.channelId().toString(),
                new com.lightai.client.channel.ChannelCheckCommand(
                        null, model.id().toString(), command.channelCredentialId(),
                        command.resolvedMode(), command.resolvedTimeoutMs()));
    }

    private CandidateRecord requireInModel(Connection connection, UUID parentId, CandidateRecord record) {
        aliasRepository.findLiveById(connection, parentId).orElseThrow(this::notFound);
        if (!parentId.equals(record.aliasId())) {
            throw new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID, "路由不属于指定虚拟模型");
        }
        return record;
    }

    private RouteCandidateDetail toDetail(Connection connection, CandidateRecord record) {
        UpstreamModelRecord model = modelRepository.findLiveById(connection,
                record.upstreamModelId()).orElse(null);
        ChannelRecord channel = channelRepository.findLiveById(connection, record.channelId()).orElse(null);
        String channelName = channel == null ? "" : channel.name();
        String runtimeStatus;
        String excludedReason = null;
        if (!record.enabled()) {
            runtimeStatus = RouteCandidateDetail.STATUS_DISABLED;
            excludedReason = "候选已停用";
        } else if (model == null || channel == null) {
            runtimeStatus = RouteCandidateDetail.STATUS_UNAVAILABLE;
            excludedReason = "引用的上游模型或渠道不可用";
        } else if (!model.enabled() || !channel.enabled()) {
            runtimeStatus = RouteCandidateDetail.STATUS_UNAVAILABLE;
            excludedReason = "上游模型或渠道已停用";
        } else if ("UNAVAILABLE".equals(channelHealth(connection, channel.id()))) {
            runtimeStatus = RouteCandidateDetail.STATUS_UNAVAILABLE;
            excludedReason = "渠道最近检测不可用";
        } else {
            runtimeStatus = RouteCandidateDetail.STATUS_AVAILABLE;
        }
        return new RouteCandidateDetail(
                record.id().toString(), record.aliasId().toString(),
                record.upstreamModelId().toString(),
                model == null ? "" : model.displayName(),
                model == null ? "" : model.modelId(),
                record.channelId().toString(), channelName,
                record.priority(), record.weight(), record.enabled(),
                model == null ? null : model.supportStream(),
                model == null ? null : model.supportSystemMessage(),
                model == null ? null : model.contextWindow(),
                0, runtimeStatus, excludedReason,
                draftChangeRepositoryOf(connection, record.id()), record.version(),
                record.updatedAt());
    }

    private boolean draftChangeRepositoryOf(Connection connection, UUID id) {
        try {
            var d = com.lightai.storage.dialect.DialectResolver.resolve(connection);
            try (var statement = connection.prepareStatement("SELECT 1 FROM "
                    + d.qualify(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME, "draft_change")
                    + " WHERE entity_type = 'route_candidate' AND entity_id = ?")) {
                statement.setString(1, id.toString());
                try (var rs = statement.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 渠道运行健康（object_runtime_state.connection_status），无记录视为 UNKNOWN。 */
    private String channelHealth(Connection connection, UUID channelId) {
        try {
            var d = com.lightai.storage.dialect.DialectResolver.resolve(connection);
            try (var statement = connection.prepareStatement("SELECT connection_status FROM "
                    + d.qualify(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME, "object_runtime_state")
                    + " WHERE entity_type = 'CHANNEL' AND entity_id = ?")) {
                d.bindUuid(statement, 1, channelId);
                try (var rs = statement.executeQuery()) {
                    return rs.next() && rs.getString(1) != null ? rs.getString(1) : "UNKNOWN";
                }
            }
        } catch (Exception e) {
            return "UNKNOWN";
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
        return com.lightai.admin.web.ResourceIds.parse(rawId);
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
