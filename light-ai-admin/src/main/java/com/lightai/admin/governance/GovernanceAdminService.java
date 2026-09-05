package com.lightai.admin.governance;

import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.governance.LimitPolicyDetail;
import com.lightai.client.governance.LimitPolicySaveCommand;
import com.lightai.client.governance.ReliabilityPolicyDetail;
import com.lightai.client.governance.ReliabilityPolicySaveCommand;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.runtime.capacity.CapacityStore;
import com.lightai.storage.governance.JdbcCircuitRepository;
import com.lightai.storage.governance.JdbcLimitPolicyRepository;
import com.lightai.storage.governance.JdbcReliabilityPolicyRepository;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 运行治理管理服务（BE-021/022）。
 * 策略为配置实体：草稿事务、版本与审计；同一 scope/alias 至多一条启用，
 * 冲突返回 LIMIT_POLICY_CONFLICT / RELIABILITY_POLICY_CONFLICT 并携带
 * conflicting_policy_id；运行查询只读共享状态存储。
 */
public class GovernanceAdminService {

    private static final Set<String> LIMIT_SORTABLE = Set.of("name", "updated_at", "created_at");
    private static final Set<String> RELIABILITY_SORTABLE = Set.of("name", "updated_at", "created_at");

    private final DataSource dataSource;
    private final JdbcLimitPolicyRepository limitPolicyRepository;
    private final JdbcReliabilityPolicyRepository reliabilityPolicyRepository;
    private final JdbcCircuitRepository circuitRepository;
    private final DraftWriteService draftWriteService;
    private final PageResultFactory pageResultFactory;
    private final CapacityStore capacityStore;
    private final String sourceMode;

    public GovernanceAdminService(DataSource dataSource,
                                  JdbcLimitPolicyRepository limitPolicyRepository,
                                  JdbcReliabilityPolicyRepository reliabilityPolicyRepository,
                                  JdbcCircuitRepository circuitRepository,
                                  DraftWriteService draftWriteService,
                                  PageResultFactory pageResultFactory,
                                  CapacityStore capacityStore, String sourceMode) {
        this.dataSource = dataSource;
        this.limitPolicyRepository = limitPolicyRepository;
        this.reliabilityPolicyRepository = reliabilityPolicyRepository;
        this.circuitRepository = circuitRepository;
        this.draftWriteService = draftWriteService;
        this.pageResultFactory = pageResultFactory;
        this.capacityStore = capacityStore;
        this.sourceMode = sourceMode;
    }

    // ---------- 限流策略（BE-021） ----------

    public PageResult<LimitPolicyDetail> listLimitPolicies(RequestContext context,
                                                           Map<String, String> params) {
        RequestPermissions.require(context, Permissions.LIMIT_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(params.get("page"),
                params.get("page_size"), params.get("sort"), LIMIT_SORTABLE, "updated_at desc");
        Boolean enabled = parseBoolean(params.get("enabled"));
        try (Connection connection = dataSource.getConnection()) {
            List<JdbcLimitPolicyRepository.LimitPolicyRow> rows = limitPolicyRepository.list(
                    connection, params.get("keyword"), params.get("scope_type"), enabled,
                    query.sort(), query.limit(), (int) query.offset());
            long total = limitPolicyRepository.count(connection, params.get("keyword"),
                    params.get("scope_type"), enabled);
            List<LimitPolicyDetail> items = new ArrayList<>(rows.size());
            for (var row : rows) {
                items.add(toLimitDetail(connection, row));
            }
            return pageResultFactory.create(items, total, query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "限流策略列表读取失败");
        }
    }

    public LimitPolicyDetail limitPolicyDetail(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.LIMIT_VIEW);
        UUID id = parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            var row = limitPolicyRepository.findLiveById(connection, id)
                    .orElseThrow(() -> notFound("限流策略"));
            return toLimitDetail(connection, row);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "限流策略详情读取失败");
        }
    }

    public ManagementOperationResult<LimitPolicyDetail> saveLimitPolicy(RequestContext context,
                                                                        LimitPolicySaveCommand command,
                                                                        String rawId) {
        RequestPermissions.require(context, Permissions.LIMIT_MANAGE);
        command.validate();
        boolean isUpdate = rawId != null;
        UUID id = isUpdate ? parseId(rawId) : UUID.randomUUID();
        String requestId = context.requestId();
        UUID scopeId = UUID.fromString(command.scopeId());

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                isUpdate ? "UPDATE" : "CREATE", "limit_policy", isUpdate ? id.toString() : null,
                isUpdate ? requireVersion(command.version()) : 0,
                isUpdate ? connection -> limitPolicyRepository.lockLiveById(connection, id)
                        .map(JdbcLimitPolicyRepository.LimitPolicyRow::version).orElse(null)
                        : null,
                connection -> {
                    // 保存阶段唯一启用校验（发布阶段由快照校验再拦）
                    Optional<JdbcLimitPolicyRepository.LimitPolicyRow> conflict =
                            limitPolicyRepository.findEnabledConflict(connection,
                                    command.scopeType(), scopeId, isUpdate ? id : null);
                    if (command.enabled() && conflict.isPresent()) {
                        throw conflictException(ErrorCode.LIMIT_POLICY_CONFLICT,
                                conflict.get().id());
                    }
                    JdbcLimitPolicyRepository.LimitPolicyRow row;
                    if (isUpdate) {
                        var current = limitPolicyRepository.lockLiveById(connection, id)
                                .orElseThrow(() -> notFound("限流策略"));
                        // scope_type/scope_id 创建后不可改
                        if (!current.scopeType().equals(command.scopeType())
                                || !current.scopeId().equals(scopeId)) {
                            throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                                    "作用对象创建后不可修改");
                        }
                        row = new JdbcLimitPolicyRepository.LimitPolicyRow(current.id(),
                                command.name(), current.scopeType(), current.scopeId(),
                                command.rpmLimit(), command.tpmLimit(), command.concurrentLimit(),
                                command.overflowStrategy(), command.queueTimeoutMs(),
                                command.queueMaxSize(), command.enabled(), current.version(),
                                current.createdAt(), current.updatedAt());
                    } else {
                        row = new JdbcLimitPolicyRepository.LimitPolicyRow(id, command.name(),
                                command.scopeType(), scopeId, command.rpmLimit(), command.tpmLimit(),
                                command.concurrentLimit(), command.overflowStrategy(),
                                command.queueTimeoutMs(), command.queueMaxSize(), command.enabled(),
                                1L, OffsetDateTime.now(), OffsetDateTime.now());
                    }
                    if (isUpdate) {
                        limitPolicyRepository.update(connection, row);
                    } else {
                        limitPolicyRepository.insert(connection, row);
                    }
                    return new DraftEntityChange("limit_policy", id, command.name(),
                            isUpdate ? "UPDATE" : "CREATE", row.version(),
                            List.of(FieldChange.changed("enabled", null, command.enabled())));
                }));

        try (Connection connection = dataSource.getConnection()) {
            var row = limitPolicyRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toLimitDetail(connection, row), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "结果读取失败");
        }
    }

    public ManagementOperationResult<LimitPolicyDetail> setLimitPolicyEnabled(RequestContext context,
                                                                              String rawId,
                                                                              boolean enabled,
                                                                              Long version) {
        RequestPermissions.require(context, Permissions.LIMIT_MANAGE);
        UUID id = parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                enabled ? "ENABLE" : "DISABLE", "limit_policy", id.toString(), version,
                connection -> limitPolicyRepository.lockLiveById(connection, id)
                        .map(JdbcLimitPolicyRepository.LimitPolicyRow::version).orElse(null),
                connection -> {
                    var current = limitPolicyRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> notFound("限流策略"));
                    if (enabled) {
                        var conflict = limitPolicyRepository.findEnabledConflict(connection,
                                current.scopeType(), current.scopeId(), id);
                        if (conflict.isPresent()) {
                            throw conflictException(ErrorCode.LIMIT_POLICY_CONFLICT,
                                    conflict.get().id());
                        }
                        boolean hasLimit = current.rpmLimit() != null || current.tpmLimit() != null
                                || current.concurrentLimit() != null;
                        if (!hasLimit) {
                            throw fieldError("limits", "REQUIRED", "启用要求至少一个限额");
                        }
                    }
                    var saved = limitPolicyRepository.update(connection,
                            new JdbcLimitPolicyRepository.LimitPolicyRow(current.id(),
                                    current.name(), current.scopeType(), current.scopeId(),
                                    current.rpmLimit(), current.tpmLimit(), current.concurrentLimit(),
                                    current.overflowStrategy(), current.queueTimeoutMs(),
                                    current.queueMaxSize(), enabled, current.version(),
                                    current.createdAt(), current.updatedAt()));
                    return new DraftEntityChange("limit_policy", id, current.name(),
                            enabled ? "ENABLE" : "DISABLE", saved.version(),
                            List.of(FieldChange.changed("enabled", current.enabled(), enabled)));
                }));

        try (Connection connection = dataSource.getConnection()) {
            var row = limitPolicyRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toLimitDetail(connection, row), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "结果读取失败");
        }
    }

    /** 限流策略当前窗口用量（只读，不创建业务计数）。 */
    public LimitPolicyDetail limitUsage(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.LIMIT_MANAGE);
        UUID id = parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            var row = limitPolicyRepository.findLiveById(connection, id)
                    .orElseThrow(() -> notFound("限流策略"));
            if (capacityStore == null) {
                throw new LightAiException(ErrorCode.CAPACITY_STATE_UNAVAILABLE,
                        "容量状态存储当前不可用");
            }
            return toLimitDetail(connection, row);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CAPACITY_STATE_UNAVAILABLE, "用量读取失败");
        }
    }

    public ManagementOperationResult<LimitPolicyDetail> deleteLimitPolicy(RequestContext context,
                                                                          String rawId, Long version) {
        RequestPermissions.require(context, Permissions.LIMIT_MANAGE);
        UUID id = parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "DELETE", "limit_policy", id.toString(), version,
                connection -> limitPolicyRepository.lockLiveById(connection, id)
                        .map(JdbcLimitPolicyRepository.LimitPolicyRow::version).orElse(null),
                connection -> {
                    var current = limitPolicyRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> notFound("限流策略"));
                    if (current.enabled()) {
                        throw fieldError("enabled", "INVALID", "启用的策略需先停用再删除");
                    }
                    limitPolicyRepository.markDeleted(connection, id);
                    return new DraftEntityChange("limit_policy", id, current.name(),
                            "DELETE", current.version(), List.of());
                }));
        return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                null, true, result.draftRevision(), requestId);
    }

    // ---------- 可靠性策略（BE-022） ----------

    public PageResult<ReliabilityPolicyDetail> listReliabilityPolicies(RequestContext context,
                                                                       Map<String, String> params) {
        RequestPermissions.require(context, Permissions.RELIABILITY_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(params.get("page"),
                params.get("page_size"), params.get("sort"), RELIABILITY_SORTABLE, "updated_at desc");
        UUID aliasId = params.get("alias_id") == null || params.get("alias_id").isBlank()
                ? null : parseId(params.get("alias_id"));
        Boolean enabled = parseBoolean(params.get("enabled"));
        try (Connection connection = dataSource.getConnection()) {
            List<JdbcReliabilityPolicyRepository.ReliabilityPolicyRow> rows =
                    reliabilityPolicyRepository.list(connection, params.get("keyword"), aliasId,
                            enabled, query.sort(), query.limit(), (int) query.offset());
            long total = reliabilityPolicyRepository.count(connection, params.get("keyword"),
                    aliasId, enabled);
            List<ReliabilityPolicyDetail> items = new ArrayList<>(rows.size());
            for (var row : rows) {
                items.add(toReliabilityDetail(row));
            }
            return pageResultFactory.create(items, total, query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "可靠性策略列表读取失败");
        }
    }

    public ReliabilityPolicyDetail reliabilityDefault(RequestContext context) {
        RequestPermissions.require(context, Permissions.RELIABILITY_VIEW);
        return ReliabilityPolicyDetail.systemDefault();
    }

    public ReliabilityPolicyDetail reliabilityDetail(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.RELIABILITY_VIEW);
        UUID id = parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            var row = reliabilityPolicyRepository.findLiveById(connection, id)
                    .orElseThrow(() -> notFound("可靠性策略"));
            return toReliabilityDetail(row);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "可靠性策略详情读取失败");
        }
    }

    public ManagementOperationResult<ReliabilityPolicyDetail> saveReliabilityPolicy(
            RequestContext context, ReliabilityPolicySaveCommand command, String rawId) {
        RequestPermissions.require(context, Permissions.RELIABILITY_MANAGE);
        command.validate();
        boolean isUpdate = rawId != null;
        UUID id = isUpdate ? parseId(rawId) : UUID.randomUUID();
        String requestId = context.requestId();
        UUID aliasId = UUID.fromString(command.aliasId());

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                isUpdate ? "UPDATE" : "CREATE", "reliability_policy",
                isUpdate ? id.toString() : null,
                isUpdate ? requireVersion(command.version()) : 0,
                isUpdate ? connection -> reliabilityPolicyRepository.lockLiveById(connection, id)
                        .map(JdbcReliabilityPolicyRepository.ReliabilityPolicyRow::version)
                        .orElse(null) : null,
                connection -> {
                    Optional<JdbcReliabilityPolicyRepository.ReliabilityPolicyRow> conflict =
                            reliabilityPolicyRepository.findEnabledConflict(connection, aliasId,
                                    isUpdate ? id : null);
                    if (command.enabled() && conflict.isPresent()) {
                        throw conflictException(ErrorCode.RELIABILITY_POLICY_CONFLICT,
                                conflict.get().id());
                    }
                    JdbcReliabilityPolicyRepository.ReliabilityPolicyRow row;
                    if (isUpdate) {
                        var current = reliabilityPolicyRepository.lockLiveById(connection, id)
                                .orElseThrow(() -> notFound("可靠性策略"));
                        if (!current.aliasId().equals(aliasId)) {
                            throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                                    "关联 Alias 创建后不可修改");
                        }
                        row = new JdbcReliabilityPolicyRepository.ReliabilityPolicyRow(
                                current.id(), command.name(), current.aliasId(),
                                command.connectTimeoutMs(), command.firstTokenTimeoutMs(),
                                command.totalTimeoutMs(), command.maxRetries(),
                                command.maxCredentialFailovers(), command.initialBackoffMs(),
                                command.backoffMultiplier(), command.jitterPercent(),
                                command.respectRetryAfter(), command.maxRetryAfterMs(),
                                command.fallbackEnabled(), command.maxFallbacks(),
                                command.circuitWindowSeconds(), command.circuitMinRequests(),
                                command.circuitFailureRate(), command.circuitOpenSeconds(),
                                command.circuitHalfOpenProbes(), command.circuitHalfOpenSuccesses(),
                                command.enabled(), current.version(), current.createdAt(),
                                current.updatedAt());
                    } else {
                        row = new JdbcReliabilityPolicyRepository.ReliabilityPolicyRow(
                                id, command.name(), aliasId, command.connectTimeoutMs(),
                                command.firstTokenTimeoutMs(), command.totalTimeoutMs(),
                                command.maxRetries(), command.maxCredentialFailovers(),
                                command.initialBackoffMs(), command.backoffMultiplier(),
                                command.jitterPercent(), command.respectRetryAfter(),
                                command.maxRetryAfterMs(), command.fallbackEnabled(),
                                command.maxFallbacks(), command.circuitWindowSeconds(),
                                command.circuitMinRequests(), command.circuitFailureRate(),
                                command.circuitOpenSeconds(), command.circuitHalfOpenProbes(),
                                command.circuitHalfOpenSuccesses(), command.enabled(), 1L,
                                OffsetDateTime.now(), OffsetDateTime.now());
                    }
                    if (isUpdate) {
                        reliabilityPolicyRepository.update(connection, row);
                    } else {
                        reliabilityPolicyRepository.insert(connection, row);
                    }
                    return new DraftEntityChange("reliability_policy", id, command.name(),
                            isUpdate ? "UPDATE" : "CREATE", row.version(),
                            List.of(FieldChange.changed("name", null, command.name())));
                }));

        try (Connection connection = dataSource.getConnection()) {
            var row = reliabilityPolicyRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toReliabilityDetail(row), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "结果读取失败");
        }
    }

    // ---------- 内部 ----------

    private LimitPolicyDetail toLimitDetail(Connection connection,
                                            JdbcLimitPolicyRepository.LimitPolicyRow row) {
        var usage = capacityStore == null ? null
                : safeUsage(row.scopeType(), row.scopeId());
        return new LimitPolicyDetail(
                row.id().toString(), row.name(), row.scopeType(), row.scopeId().toString(),
                row.rpmLimit(), row.tpmLimit(), row.concurrentLimit(), row.overflowStrategy(),
                row.queueTimeoutMs(), row.queueMaxSize(), row.enabled(),
                draftChangeExists(connection, "limit_policy", row.id()), row.version(),
                usage == null ? null : usage.rpmReserved(),
                usage == null ? null : usage.tpmReserved(),
                usage == null ? null : (int) usage.concurrentActive(),
                null,
                capacityStore == null ? "UNAVAILABLE" : "AVAILABLE",
                row.updatedAt());
    }

    private CapacityStore.UsageSnapshot safeUsage(String scopeType, UUID scopeId) {
        try {
            return capacityStore.usage(scopeType, scopeId);
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CAPACITY_STATE_UNAVAILABLE, "容量状态存储不可用");
        }
    }

    private boolean draftChangeExists(Connection connection, String entityType, UUID entityId) {
        try {
            return draftChangeLookup(connection, entityType, entityId);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean draftChangeLookup(Connection connection, String entityType, UUID entityId) {
        String sql = "SELECT 1 FROM " + schemaName()
                + ".draft_change WHERE entity_type = ? AND entity_id = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityType);
            statement.setObject(2, entityId);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private ReliabilityPolicyDetail toReliabilityDetail(
            JdbcReliabilityPolicyRepository.ReliabilityPolicyRow row) {
        return new ReliabilityPolicyDetail(row.id().toString(), row.name(),
                row.aliasId().toString(), row.connectTimeoutMs(), row.firstTokenTimeoutMs(),
                row.totalTimeoutMs(), row.maxRetries(), row.maxCredentialFailovers(),
                row.initialBackoffMs(), row.backoffMultiplier(), row.jitterPercent(),
                row.respectRetryAfter(), row.maxRetryAfterMs(), row.fallbackEnabled(),
                row.maxFallbacks(), row.circuitWindowSeconds(), row.circuitMinRequests(),
                row.circuitFailureRate(), row.circuitOpenSeconds(), row.circuitHalfOpenProbes(),
                row.circuitHalfOpenSuccesses(), row.enabled(), false, row.version(),
                row.updatedAt());
    }

    private static LightAiException conflictException(ErrorCode code, UUID conflictingPolicyId) {
        return new LightAiException(code, "同作用对象已存在另一条启用的策略",
                conflictingPolicyId.toString());
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

    private static LightAiException notFound(String what) {
        return new LightAiException(ErrorCode.OBJECT_NOT_FOUND, what + "不存在或已删除");
    }

    private static LightAiException fieldError(String field, String code, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "字段校验失败",
                List.of(new FieldIssue(field, code, message)));
    }

    private String schemaName() {
        return com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME;
    }
}
