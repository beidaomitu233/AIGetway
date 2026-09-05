package com.lightai.admin.provider;

import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.draft.DraftEntityChange;
import com.lightai.admin.draft.DraftWriteCommand;
import com.lightai.admin.draft.DraftWriteResult;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.provider.HeaderPolicies;
import com.lightai.client.provider.ProviderCheckCommand;
import com.lightai.client.provider.ProviderCheckRecord;
import com.lightai.client.provider.ProviderDetail;
import com.lightai.client.provider.ProviderListItem;
import com.lightai.client.provider.ProviderSaveCommand;
import com.lightai.storage.check.CheckRecordRow;
import com.lightai.storage.check.JdbcProviderCheckRecordRepository;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.provider.JdbcProviderRepository;
import com.lightai.storage.provider.ProviderRecord;
import com.lightai.storage.reference.JdbcConfigReferenceRepository;
import com.lightai.storage.runtime.JdbcObjectRuntimeStateRepository;
import com.lightai.storage.runtime.JdbcRuntimeStateWriter;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Provider 管理服务（BE-007/008/009/010）。
 * 读取组合草稿配置与运行状态、引用计数，序列化前剥离敏感字段；
 * 写入经草稿锁与乐观版本事务（DraftWriteService），启停删除仅发布后生效。
 * 运行状态（connection_status 等）由检测即时更新，不进入草稿。
 */
public class ProviderService {

    public static final String ENTITY_TYPE = "PROVIDER";
    private static final Set<String> SORTABLE = Set.of("name", "type", "updated_at", "created_at");
    private static final Set<String> CONNECTION_STATUSES = Set.of("UNKNOWN", "AVAILABLE", "UNAVAILABLE");
    private static final int DETAIL_CHECK_RECORDS = 10;

    private final DataSource dataSource;
    private final JdbcProviderRepository providerRepository;
    private final JdbcConfigReferenceRepository referenceRepository;
    private final JdbcObjectRuntimeStateRepository runtimeStateRepository;
    private final JdbcRuntimeStateWriter runtimeStateWriter;
    private final JdbcProviderCheckRecordRepository checkRecordRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final DraftWriteService draftWriteService;
    private final ImpactService impactService;
    private final ProviderTypeRegistry typeRegistry;
    private final TargetUrlPolicy targetUrlPolicy;
    private final PageResultFactory pageResultFactory;
    private final String sourceMode;

    public ProviderService(DataSource dataSource, JdbcProviderRepository providerRepository,
                           JdbcConfigReferenceRepository referenceRepository,
                           JdbcObjectRuntimeStateRepository runtimeStateRepository,
                           JdbcRuntimeStateWriter runtimeStateWriter,
                           JdbcProviderCheckRecordRepository checkRecordRepository,
                           DraftChangeRepository draftChangeRepository,
                           DraftWriteService draftWriteService, ImpactService impactService,
                           ProviderTypeRegistry typeRegistry, TargetUrlPolicy targetUrlPolicy,
                           PageResultFactory pageResultFactory, String sourceMode) {
        this.dataSource = dataSource;
        this.providerRepository = providerRepository;
        this.referenceRepository = referenceRepository;
        this.runtimeStateRepository = runtimeStateRepository;
        this.runtimeStateWriter = runtimeStateWriter;
        this.checkRecordRepository = checkRecordRepository;
        this.draftChangeRepository = draftChangeRepository;
        this.draftWriteService = draftWriteService;
        this.impactService = impactService;
        this.typeRegistry = typeRegistry;
        this.targetUrlPolicy = targetUrlPolicy;
        this.pageResultFactory = pageResultFactory;
        this.sourceMode = sourceMode;
    }

    // ---------- 读取（BE-007） ----------

    public PageResult<ProviderListItem> list(RequestContext context, Map<String, String> params) {
        RequestPermissions.require(context, Permissions.PROVIDER_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(
                params.get("page"), params.get("page_size"), params.get("sort"),
                SORTABLE, "updated_at desc");
        JdbcProviderRepository.ProviderFilter filter = new JdbcProviderRepository.ProviderFilter(
                params.get("keyword"), params.get("type"), parseBoolean(params.get("enabled")),
                validateStatus(params.get("connection_status")), parseBoolean(params.get("draft_changed")));

        try (Connection connection = dataSource.getConnection()) {
            List<ProviderRecord> records = providerRepository.list(connection, filter,
                    query.sort(), query.limit(), (int) query.offset());
            long total = providerRepository.count(connection, filter);
            List<ProviderListItem> items = composeListItems(connection, records);
            return pageResultFactory.create(items, total, query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "配置列表当前无法读取");
        }
    }

    public ProviderDetail detail(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.PROVIDER_VIEW);
        UUID id = parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            ProviderRecord record = providerRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "Provider不存在或已删除"));
            return toDetail(connection, record);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "配置详情当前无法读取");
        }
    }

    private List<ProviderListItem> composeListItems(Connection connection, List<ProviderRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = records.stream().map(ProviderRecord::id).toList();
        Map<UUID, JdbcObjectRuntimeStateRepository.RuntimeStateSnapshot> states =
                runtimeStateWriter.findByEntities(connection, ENTITY_TYPE, ids);
        Map<UUID, Long> modelCounts = referenceRepository.countProviderModelsByProviders(connection, ids);
        Map<UUID, Long> poolCounts = referenceRepository.countPoolsByProviders(connection, ids);
        Set<UUID> changed = draftChangeRepository.findChangedEntityIds(connection, ENTITY_TYPE, ids);
        Map<UUID, CheckRecordRow> latestChecks = new HashMap<>();
        for (CheckRecordRow row : checkRecordRepository.findLatestByTargets(connection,
                CheckRecordRow.TARGET_PROVIDER, ids)) {
            latestChecks.put(row.targetId(), row);
        }

        List<ProviderListItem> items = new ArrayList<>(records.size());
        for (ProviderRecord record : records) {
            var state = states.get(record.id());
            CheckRecordRow latest = latestChecks.get(record.id());
            items.add(new ProviderListItem(
                    record.id().toString(),
                    record.name(),
                    record.type(),
                    record.baseUrl(),
                    record.proxyUrl(),
                    state == null ? "UNKNOWN" : state.connectionStatusOrDefault(),
                    state == null ? null : state.lastCheckedAt(),
                    latest == null ? null : (long) latest.totalMs(),
                    state == null ? null : state.lastErrorCode(),
                    modelCounts.getOrDefault(record.id(), 0L),
                    poolCounts.getOrDefault(record.id(), 0L),
                    record.enabled(),
                    changed.contains(record.id()),
                    record.version(),
                    record.updatedAt()));
        }
        return List.copyOf(items);
    }

    private ProviderDetail toDetail(Connection connection, ProviderRecord record) {
        var state = runtimeStateRepository.findByEntity(connection, ENTITY_TYPE, record.id()).orElse(null);
        List<ProviderCheckRecord> recent = checkRecordRepository
                .findLatestByTarget(connection, CheckRecordRow.TARGET_PROVIDER, record.id(), DETAIL_CHECK_RECORDS)
                .stream().map(this::toCheckRecord).toList();
        String updatedBy = draftChangeRepository
                .findLatestModifier(connection, ENTITY_TYPE, record.id()).orElse("");
        return new ProviderDetail(
                record.id().toString(), record.name(), record.type(), record.baseUrl(),
                record.proxyUrl(),
                state == null ? "UNKNOWN" : state.connectionStatusOrDefault(),
                state == null ? null : state.lastCheckedAt(),
                recent.isEmpty() ? null : (long) recent.get(0).totalMs(),
                state == null ? null : state.lastErrorCode(),
                record.enabled(),
                draftChangeRepository.findChangedEntityIds(connection, ENTITY_TYPE,
                        List.of(record.id())).contains(record.id()),
                record.version(),
                record.connectTimeoutMs(),
                record.readTimeoutMs(),
                record.defaultHeaders(),
                updatedBy,
                record.createdAt(),
                updatedBy,
                record.updatedAt(),
                recent);
    }

    private ProviderCheckRecord toCheckRecord(CheckRecordRow row) {
        return new ProviderCheckRecord(
                row.id().toString(), row.targetType(), row.targetId().toString(), row.mode(),
                row.status(), row.startedAt(), row.endedAt(), row.totalMs(), row.traceId(),
                row.attemptId(), row.usage(), row.errorCode(), row.errorSummary(),
                row.providerRequestId());
    }

    // ---------- 写入（BE-008/010） ----------

    public ManagementOperationResult<ProviderDetail> create(RequestContext context,
                                                            ProviderSaveCommand command) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        validateCommand(command, null);
        UUID id = UUID.randomUUID();
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "CREATE", ENTITY_TYPE.toLowerCase(), null, 0, null,
                connection -> {
                    ProviderRecord record = new ProviderRecord(id, command.name(), command.type(),
                            command.baseUrl(), command.proxyUrl(), command.connectTimeoutMs(),
                            command.readTimeoutMs(), command.defaultHeaders(), command.enabled(),
                            1L, OffsetDateTime.now(), OffsetDateTime.now());
                    providerRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, command.name(),
                            "CREATE", 1L, createChanges(null, command));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ProviderRecord record = providerRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "创建结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "创建结果读取失败");
        }
    }

    public ManagementOperationResult<ProviderDetail> update(RequestContext context, String rawId,
                                                            ProviderSaveCommand command) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        UUID id = parseId(rawId);
        validateCommand(command, id);
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE.toLowerCase(), id.toString(), requireVersion(command.version()),
                connection -> providerRepository.lockLiveById(connection, id)
                        .map(ProviderRecord::version).orElse(null),
                connection -> {
                    ProviderRecord current = providerRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "Provider不存在或已删除"));
                    ProviderRecord updated = new ProviderRecord(current.id(), command.name(),
                            current.type(), command.baseUrl(), command.proxyUrl(),
                            command.connectTimeoutMs(), command.readTimeoutMs(),
                            command.defaultHeaders(), command.enabled(), current.version(),
                            current.createdAt(), current.updatedAt());
                    ProviderRecord saved = providerRepository.update(connection, updated);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, command.name(),
                            "UPDATE", saved.version(), createChanges(current, command));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ProviderRecord record = providerRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "更新结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "更新结果读取失败");
        }
    }

    public ManagementOperationResult<ProviderDetail> setEnabled(RequestContext context, String rawId,
                                                                boolean enabled, Long version,
                                                                String confirmedImpactVersion) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        UUID id = parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        try (Connection connection = dataSource.getConnection()) {
            ProviderRecord current = providerRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                            "Provider不存在或已删除"));
            // 停用需要确认影响；引用关系变化后票据失效
            if (!enabled) {
                impactService.verifyConfirmedImpact(confirmedImpactVersion,
                        impactService.analyzeProvider(connection, current.id(), current.name()));
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "配置状态读取失败");
        }

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                enabled ? "ENABLE" : "DISABLE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> providerRepository.lockLiveById(connection, id)
                        .map(ProviderRecord::version).orElse(null),
                connection -> {
                    ProviderRecord current = providerRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "Provider不存在或已删除"));
                    ProviderRecord saved = providerRepository.setEnabled(connection, id, enabled);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            enabled ? "ENABLE" : "DISABLE", saved.version(),
                            List.of(FieldChange.changed("enabled", current.enabled(), enabled)));
                }));

        try (Connection connection = dataSource.getConnection()) {
            ProviderRecord record = providerRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "操作结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    toDetail(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "操作结果读取失败");
        }
    }

    public ManagementOperationResult<ProviderDetail> delete(RequestContext context, String rawId,
                                                            Long version, String confirmedImpactVersion) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        UUID id = parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        try (Connection connection = dataSource.getConnection()) {
            ProviderRecord current = providerRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                            "Provider不存在或已删除"));
            var fresh = impactService.analyzeProvider(connection, current.id(), current.name());
            impactService.verifyConfirmedImpact(confirmedImpactVersion, fresh);
            if (!fresh.canDelete()) {
                throw new LightAiException(ErrorCode.OBJECT_IN_USE,
                        "Provider仍被其他配置引用，不能删除");
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "配置状态读取失败");
        }

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "DELETE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> providerRepository.lockLiveById(connection, id)
                        .map(ProviderRecord::version).orElse(null),
                connection -> {
                    ProviderRecord current = providerRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "Provider不存在或已删除"));
                    providerRepository.markDeleted(connection, id);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            "DELETE", current.version(), List.of());
                }));

        return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                null, true, result.draftRevision(), requestId);
    }

    /** 影响分析（BE-010）：以当前草稿与活动引用关系计算摘要票据。 */
    public com.lightai.client.impact.ImpactAnalysis impact(RequestContext context, String rawId,
                                                           String operation) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        if (!ImpactService.OPERATION_DISABLE.equals(operation)
                && !ImpactService.OPERATION_DELETE.equals(operation)) {
            throw fieldError("operation", "INVALID", "operation 仅支持 DISABLE/DELETE");
        }
        UUID id = parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            ProviderRecord record = providerRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                            "Provider不存在或已删除"));
            return impactService.analyzeProvider(connection, record.id(), record.name());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响分析当前无法读取");
        }
    }

    // ---------- 校验（BE-008） ----------

    private void validateCommand(ProviderSaveCommand command, UUID selfId) {
        typeRegistry.requireRegistered(command.type());
        for (Map.Entry<String, String> entry : command.defaultHeaders().entrySet()) {
            if (HeaderPolicies.isAuthHeader(entry.getKey())) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "请求头不合法",
                        List.of(new FieldIssue("default_headers." + entry.getKey(), "AUTH_HEADER",
                                "不允许配置认证类请求头，密钥请使用 Credential")));
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            String baseUrl = targetUrlPolicy.validate(command.baseUrl(), "base_url");
            if (command.proxyUrl() != null) {
                targetUrlPolicy.validate(command.proxyUrl(), "proxy_url");
            }
            boolean nameTaken = selfId == null
                    ? providerRepository.existsByLiveName(connection, command.name())
                    : providerRepository.existsByLiveNameExcept(connection, command.name(), selfId);
            if (nameTaken) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "Provider名称已存在",
                        List.of(new FieldIssue("name", "DUPLICATED",
                                "名称已存在，Provider名称全局唯一")));
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "配置校验当前无法执行");
        }
    }

    private List<FieldChange> createChanges(ProviderRecord before, ProviderSaveCommand command) {
        List<FieldChange> changes = new ArrayList<>();
        if (before == null) {
            changes.add(FieldChange.changed("name", null, command.name()));
            changes.add(FieldChange.changed("base_url", null, command.baseUrl()));
            changes.add(FieldChange.changed("enabled", null, command.enabled()));
            return List.copyOf(changes);
        }
        if (!before.name().equals(command.name())) {
            changes.add(FieldChange.changed("name", before.name(), command.name()));
        }
        if (!before.baseUrl().equals(command.baseUrl())) {
            changes.add(FieldChange.changed("base_url", before.baseUrl(), command.baseUrl()));
        }
        if (before.proxyUrl() == null ^ command.proxyUrl() == null
                || (before.proxyUrl() != null && !before.proxyUrl().equals(command.proxyUrl()))) {
            changes.add(FieldChange.changed("proxy_url", before.proxyUrl(), command.proxyUrl()));
        }
        if (before.connectTimeoutMs() != command.connectTimeoutMs()) {
            changes.add(FieldChange.changed("connect_timeout_ms", before.connectTimeoutMs(),
                    command.connectTimeoutMs()));
        }
        if (before.readTimeoutMs() != command.readTimeoutMs()) {
            changes.add(FieldChange.changed("read_timeout_ms", before.readTimeoutMs(),
                    command.readTimeoutMs()));
        }
        if (!before.defaultHeaders().equals(command.defaultHeaders())) {
            changes.add(FieldChange.changed("default_headers.headers_count",
                    before.defaultHeaders().size(), command.defaultHeaders().size()));
        }
        if (before.enabled() != command.enabled()) {
            changes.add(FieldChange.changed("enabled", before.enabled(), command.enabled()));
        }
        return List.copyOf(changes);
    }

    // ---------- 通用 ----------

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

    private static String validateStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!CONNECTION_STATUSES.contains(raw.strip())) {
            throw fieldError("connection_status", "INVALID", "状态仅支持 UNKNOWN/AVAILABLE/UNAVAILABLE");
        }
        return raw.strip();
    }

    private static long requireVersion(Long version) {
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "编辑操作必须提交正整数 version");
        }
        return version;
    }

    private static LightAiException fieldError(String field, String code, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "查询参数不合法",
                List.of(new FieldIssue(field, code, message)));
    }
}
