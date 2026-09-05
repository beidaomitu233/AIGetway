package com.lightai.admin.pool;

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
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.pool.CredentialPoolDetail;
import com.lightai.client.pool.CredentialPoolListItem;
import com.lightai.client.pool.PoolSaveCommand;
import com.lightai.client.protocol.Permissions;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.pool.JdbcPoolRepository;
import com.lightai.storage.pool.PoolRecord;
import com.lightai.storage.reference.JdbcConfigReferenceRepository;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 凭证池管理服务（BE-011/BE-012）。
 * 池内名称唯一；provider_id 创建后不可修改；运行状态由池启停与
 * 凭证健康计数派生；删除被凭证或候选引用时拒绝（OBJECT_IN_USE）。
 */
public class PoolService {

    public static final String ENTITY_TYPE = "CREDENTIAL_POOL";
    private static final Set<String> SORTABLE = Set.of("name", "updated_at", "created_at");

    private final DataSource dataSource;
    private final JdbcPoolRepository poolRepository;
    private final JdbcConfigReferenceRepository referenceRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final DraftWriteService draftWriteService;
    private final ImpactService impactService;
    private final PageResultFactory pageResultFactory;
    private final String sourceMode;
    private final String schemaName;

    public PoolService(DataSource dataSource, JdbcPoolRepository poolRepository,
                       JdbcConfigReferenceRepository referenceRepository,
                       DraftChangeRepository draftChangeRepository,
                       DraftWriteService draftWriteService, ImpactService impactService,
                       PageResultFactory pageResultFactory, String sourceMode) {
        this(dataSource, poolRepository, referenceRepository, draftChangeRepository,
                draftWriteService, impactService, pageResultFactory, sourceMode,
                com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public PoolService(DataSource dataSource, JdbcPoolRepository poolRepository,
                       JdbcConfigReferenceRepository referenceRepository,
                       DraftChangeRepository draftChangeRepository,
                       DraftWriteService draftWriteService, ImpactService impactService,
                       PageResultFactory pageResultFactory, String sourceMode, String schemaName) {
        this.dataSource = dataSource;
        this.poolRepository = poolRepository;
        this.referenceRepository = referenceRepository;
        this.draftChangeRepository = draftChangeRepository;
        this.draftWriteService = draftWriteService;
        this.impactService = impactService;
        this.pageResultFactory = pageResultFactory;
        this.sourceMode = sourceMode;
        this.schemaName = schemaName;
    }
    // ---------- 读取（BE-011） ----------

    public PageResult<CredentialPoolListItem> list(RequestContext context, Map<String, String> params) {
        RequestPermissions.require(context, Permissions.PROVIDER_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(
                params.get("page"), params.get("page_size"), params.get("sort"),
                SORTABLE, "updated_at desc");
        UUID providerId = params.get("provider_id") == null || params.get("provider_id").isBlank()
                ? null
                : ProviderService.parseId(params.get("provider_id"));
        JdbcPoolRepository.PoolFilter filter = new JdbcPoolRepository.PoolFilter(
                params.get("keyword"), providerId, parseBoolean(params.get("enabled")));

        try (Connection connection = dataSource.getConnection()) {
            List<JdbcPoolRepository.PoolRow> rows = poolRepository.listRows(connection, filter,
                    query.sort(), query.limit(), (int) query.offset());
            long total = poolRepository.count(connection, filter);
            List<CredentialPoolListItem> items = new ArrayList<>(rows.size());
            for (JdbcPoolRepository.PoolRow row : rows) {
                items.add(toListItem(connection, row.pool(), row.providerName()));
            }
            return pageResultFactory.create(items, total, query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "配置列表当前无法读取");
        }
    }

    public CredentialPoolDetail detail(RequestContext context, String rawId) {
        RequestPermissions.require(context, Permissions.PROVIDER_VIEW);
        UUID id = ProviderService.parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            PoolRecord record = poolRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "凭证池不存在或已删除"));
            String providerName = providerName(connection, record.providerId());
            var counts = referenceRepository.countCredentialsByPool(connection, id);
            long candidates = referenceRepository.countRouteCandidates(connection, id);
            long aliases = referenceRepository.countAliasesByPool(connection, id);
            String modifier = draftChangeRepository
                    .findLatestModifier(connection, ENTITY_TYPE, id).orElse("");
            return new CredentialPoolDetail(
                    record.id().toString(), record.providerId().toString(), providerName,
                    record.name(), record.selectionStrategy(),
                    counts.total(), counts.enabledCount(), 0, 0, 0,
                    CredentialPoolListItem.deriveStatus(record.enabled(), counts.total(), counts.enabledCount()),
                    record.enabled(),
                    draftChangeRepository.findChangedEntityIds(connection, ENTITY_TYPE,
                            List.of(id)).contains(id),
                    record.version(), candidates, aliases, modifier,
                    record.createdAt(), modifier, record.updatedAt());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "配置详情当前无法读取");
        }
    }

    private CredentialPoolListItem toListItem(Connection connection, PoolRecord record,
                                              String providerName) {
        var counts = referenceRepository.countCredentialsByPool(connection, record.id());
        return new CredentialPoolListItem(
                record.id().toString(), record.providerId().toString(), providerName,
                record.name(), record.selectionStrategy(),
                counts.total(), counts.enabledCount(), 0, 0, 0,
                CredentialPoolListItem.deriveStatus(record.enabled(), counts.total(), counts.enabledCount()),
                record.enabled(),
                draftChangeRepository.findChangedEntityIds(connection, ENTITY_TYPE,
                        List.of(record.id())).contains(record.id()),
                record.version(), record.updatedAt());
    }

    // ---------- 写入（BE-011/012） ----------

    public ManagementOperationResult<CredentialPoolDetail> create(RequestContext context,
                                                                  PoolSaveCommand command) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        UUID id = UUID.randomUUID();
        String requestId = context.requestId();
        requireProviderExists(command.providerId());

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "CREATE", ENTITY_TYPE.toLowerCase(), null, 0, null,
                connection -> {
                    if (poolRepository.existsByLiveNameInProvider(connection, command.providerId(),
                            command.name())) {
                        throw nameConflict();
                    }
                    PoolRecord record = new PoolRecord(id, command.providerId(), command.name(),
                            command.selectionStrategy().name(), command.enabled(), 1L,
                            OffsetDateTime.now(), OffsetDateTime.now());
                    poolRepository.insert(connection, record);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, command.name(),
                            "CREATE", 1L, List.of(FieldChange.changed("name", null, command.name())));
                }));

        try (Connection connection = dataSource.getConnection()) {
            PoolRecord record = poolRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "创建结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    detailById(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "创建结果读取失败");
        }
    }

    public ManagementOperationResult<CredentialPoolDetail> update(RequestContext context, String rawId,
                                                                  PoolSaveCommand command) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        UUID id = ProviderService.parseId(rawId);
        String requestId = context.requestId();

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "UPDATE", ENTITY_TYPE.toLowerCase(), id.toString(), requireVersion(command.version()),
                connection -> poolRepository.lockLiveById(connection, id)
                        .map(PoolRecord::version).orElse(null),
                connection -> {
                    PoolRecord current = poolRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "凭证池不存在或已删除"));
                    // provider_id 创建后不可修改：命令值与现值不一致即拒绝
                    if (!current.providerId().equals(command.providerId())) {
                        throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                                "凭证池所属 Provider 不可修改");
                    }
                    if (!current.name().equals(command.name())
                            && poolRepository.existsByLiveNameInProvider(connection,
                                    current.providerId(), command.name())) {
                        throw nameConflict();
                    }
                    PoolRecord saved = poolRepository.update(connection, new PoolRecord(
                            current.id(), current.providerId(), command.name(),
                            command.selectionStrategy().name(), command.enabled(),
                            current.version(), current.createdAt(), current.updatedAt()));
                    List<FieldChange> changes = new ArrayList<>();
                    if (!current.name().equals(command.name())) {
                        changes.add(FieldChange.changed("name", current.name(), command.name()));
                    }
                    if (!current.selectionStrategy().equals(command.selectionStrategy().name())) {
                        changes.add(FieldChange.changed("selection_strategy",
                                current.selectionStrategy(), command.selectionStrategy().name()));
                    }
                    if (current.enabled() != command.enabled()) {
                        changes.add(FieldChange.changed("enabled", current.enabled(), command.enabled()));
                    }
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, command.name(),
                            "UPDATE", saved.version(), List.copyOf(changes));
                }));

        try (Connection connection = dataSource.getConnection()) {
            PoolRecord record = poolRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "更新结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    detailById(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "更新结果读取失败");
        }
    }

    public ManagementOperationResult<CredentialPoolDetail> setEnabled(RequestContext context,
                                                                      String rawId, boolean enabled,
                                                                      Long version,
                                                                      String confirmedImpactVersion) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        UUID id = ProviderService.parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        if (!enabled) {
            requireFreshImpact(context, id, confirmedImpactVersion);
        }

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                enabled ? "ENABLE" : "DISABLE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> poolRepository.lockLiveById(connection, id)
                        .map(PoolRecord::version).orElse(null),
                connection -> {
                    PoolRecord current = poolRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "凭证池不存在或已删除"));
                    PoolRecord saved = poolRepository.update(connection, new PoolRecord(
                            current.id(), current.providerId(), current.name(),
                            current.selectionStrategy(), enabled, current.version(),
                            current.createdAt(), current.updatedAt()));
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            enabled ? "ENABLE" : "DISABLE", saved.version(),
                            List.of(FieldChange.changed("enabled", current.enabled(), enabled)));
                }));

        try (Connection connection = dataSource.getConnection()) {
            PoolRecord record = poolRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.INTERNAL_ERROR, "操作结果读取失败"));
            return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                    detailById(connection, record), true, result.draftRevision(), requestId);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "操作结果读取失败");
        }
    }

    public ManagementOperationResult<CredentialPoolDetail> delete(RequestContext context, String rawId,
                                                                  Long version,
                                                                  String confirmedImpactVersion) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        UUID id = ProviderService.parseId(rawId);
        if (version == null || version < 1) {
            throw fieldError("version", "REQUIRED", "version 必填");
        }
        String requestId = context.requestId();
        requireFreshImpact(context, id, confirmedImpactVersion);

        DraftWriteResult result = draftWriteService.execute(new DraftWriteCommand(
                requestId, context.authContext().userId(), sourceMode, context.sourceIpMasked(),
                "DELETE", ENTITY_TYPE.toLowerCase(), id.toString(), version,
                connection -> poolRepository.lockLiveById(connection, id)
                        .map(PoolRecord::version).orElse(null),
                connection -> {
                    PoolRecord current = poolRepository.lockLiveById(connection, id)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "凭证池不存在或已删除"));
                    // 子凭证或候选引用存在即拒绝，无部分差异写入
                    var counts = referenceRepository.countCredentialsByPool(connection, id);
                    long candidates = referenceRepository.countRouteCandidates(connection, id);
                    if (counts.total() > 0 || candidates > 0) {
                        throw new LightAiException(ErrorCode.OBJECT_IN_USE,
                                "凭证池仍被凭证或候选引用，不能删除");
                    }
                    poolRepository.markDeleted(connection, id);
                    return new DraftEntityChange(ENTITY_TYPE.toLowerCase(), id, current.name(),
                            "DELETE", current.version(), List.of());
                }));

        return new ManagementOperationResult<>(id.toString(), result.entityVersion(),
                null, true, result.draftRevision(), requestId);
    }

    public com.lightai.client.impact.ImpactAnalysis impact(RequestContext context, String rawId,
                                                           String operation) {
        RequestPermissions.require(context, Permissions.PROVIDER_MANAGE);
        if (!ImpactService.OPERATION_DISABLE.equals(operation)
                && !ImpactService.OPERATION_DELETE.equals(operation)) {
            throw fieldError("operation", "INVALID", "operation 仅支持 DISABLE/DELETE");
        }
        UUID id = ProviderService.parseId(rawId);
        try (Connection connection = dataSource.getConnection()) {
            PoolRecord record = poolRepository.findLiveById(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                            "凭证池不存在或已删除"));
            return impactService.analyzePool(connection, record.id(), record.name());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响分析当前无法读取");
        }
    }

    // ---------- 内部 ----------

    private void requireProviderExists(UUID providerId) {
        try (Connection connection = dataSource.getConnection()) {
            if (!providerExists(connection, providerId)) {
                throw new LightAiException(ErrorCode.OBJECT_REFERENCE_INVALID,
                        "引用的 Provider 不存在或已删除");
            }
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "引用校验失败");
        }
    }

    private boolean providerExists(Connection connection, UUID providerId) {
        String sql = "SELECT 1 FROM " + schemaName + ".provider WHERE id = ? AND deleted_at IS NULL";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, providerId);
            try (var rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "引用校验失败");
        }
    }

    private void requireFreshImpact(RequestContext context, UUID poolId, String confirmedImpactVersion) {
        if (confirmedImpactVersion == null || confirmedImpactVersion.isBlank()) {
            throw fieldError("confirmed_impact_version", "REQUIRED", "confirmed_impact_version 必填");
        }
        try (Connection connection = dataSource.getConnection()) {
            PoolRecord record = poolRepository.findLiveById(connection, poolId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                            "凭证池不存在或已删除"));
            impactService.verifyConfirmedImpact(confirmedImpactVersion,
                    impactService.analyzePool(connection, record.id(), record.name()));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "影响确认校验失败");
        }
    }

    private CredentialPoolDetail detailById(Connection connection, PoolRecord record) {
        String providerName = providerName(connection, record.providerId());
        var counts = referenceRepository.countCredentialsByPool(connection, record.id());
        long candidates = referenceRepository.countRouteCandidates(connection, record.id());
        long aliases = referenceRepository.countAliasesByPool(connection, record.id());
        String modifier = draftChangeRepository
                .findLatestModifier(connection, ENTITY_TYPE, record.id()).orElse("");
        return new CredentialPoolDetail(
                record.id().toString(), record.providerId().toString(), providerName,
                record.name(), record.selectionStrategy(),
                counts.total(), counts.enabledCount(), 0, 0, 0,
                CredentialPoolListItem.deriveStatus(record.enabled(), counts.total(), counts.enabledCount()),
                record.enabled(),
                draftChangeRepository.findChangedEntityIds(connection, ENTITY_TYPE,
                        List.of(record.id())).contains(record.id()),
                record.version(), candidates, aliases, modifier,
                record.createdAt(), modifier, record.updatedAt());
    }

    private String providerName(Connection connection, UUID providerId) {
        String sql = "SELECT name FROM " + schemaName
                + ".provider WHERE id = ? AND deleted_at IS NULL";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, providerId);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "配置读取失败");
        }
    }

    private static LightAiException nameConflict() {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "凭证池名称已存在",
                List.of(new FieldIssue("name", "DUPLICATED", "同一 Provider 下池名称唯一")));
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
