package com.lightai.admin.application;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.application.ApplicationDetail;
import com.lightai.client.application.ApplicationListItem;
import com.lightai.client.application.ApplicationMemberView;
import com.lightai.client.application.ApplicationModelConstraint;
import com.lightai.client.application.ApplicationModelPermissionView;
import com.lightai.client.application.ApplicationModelsUpdateCommand;
import com.lightai.client.application.ApplicationQuotaAdjustmentCommand;
import com.lightai.client.application.ApplicationQuotaAdjustmentView;
import com.lightai.client.application.ApplicationQuotaPolicyView;
import com.lightai.client.application.ApplicationQuotaResetCommand;
import com.lightai.client.application.ApplicationQuotaUpdateCommand;
import com.lightai.client.application.ApplicationStatusCommand;
import com.lightai.client.application.ApplicationUpdateCommand;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.protocol.Roles;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.application.ApplicationModelPermissionRecord;
import com.lightai.storage.application.ApplicationQuotaRecord;
import com.lightai.storage.application.ApplicationRecord;
import com.lightai.storage.application.JdbcApplicationRepository;
import com.lightai.storage.application.QuotaAdjustmentRecord;
import com.lightai.storage.audit.AuditRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** V2 应用中心服务：应用是权限、额度、模型授权和调用归属的第一业务对象。 */
public final class ApplicationService {

    private static final Set<String> SORTABLE = Set.of(
            "name", "code", "status", "environment", "owner_name", "updated_at", "last_called_at");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED", "ARCHIVED");
    private static final Set<String> CREATE_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> ENVIRONMENTS = Set.of("DEV", "TEST", "STAGING", "PROD");
    private static final Set<String> PERIOD_TYPES = Set.of("LIFECYCLE", "DAY", "MONTH", "CUSTOM");
    private static final Pattern CODE = Pattern.compile("^[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final String NO_APPLICATION = "__no_authorized_application__";

    private final DataSource dataSource;
    private final JdbcApplicationRepository repository;
    private final JdbcAliasRepository aliasRepository;
    private final AuditService auditService;
    private final TransactionTemplate transaction;
    private final PageResultFactory pageResultFactory;
    private final Clock clock;
    private final String sourceMode;

    public ApplicationService(DataSource dataSource, JdbcApplicationRepository repository,
                              JdbcAliasRepository aliasRepository, AuditService auditService,
                              PlatformTransactionManager transactionManager,
                              PageResultFactory pageResultFactory, Clock clock, String sourceMode) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.aliasRepository = aliasRepository;
        this.auditService = auditService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.pageResultFactory = pageResultFactory;
        this.clock = clock;
        this.sourceMode = sourceMode;
    }

    public PageResult<ApplicationListItem> list(RequestContext context, Map<String, String> params) {
        RequestPermissions.require(context, Permissions.APPLICATION_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(
                params.get("page"), params.get("page_size"), params.get("sort"),
                SORTABLE, "updated_at desc");
        String status = optionalEnum(params.get("status"), STATUSES, "status");
        String environment = optionalEnum(params.get("environment"), ENVIRONMENTS, "environment");
        try (Connection connection = dataSource.getConnection()) {
            List<String> scope = effectiveScope(connection, context);
            JdbcApplicationRepository.Filter filter = new JdbcApplicationRepository.Filter(
                    trimToNull(params.get("keyword")), status, environment,
                    trimToNull(params.get("owner_id")), scope);
            List<ApplicationRecord> records = repository.list(
                    connection, filter, query.sort(), query.limit(), query.offset());
            List<ApplicationListItem> items = new ArrayList<>(records.size());
            for (ApplicationRecord record : records) {
                items.add(toListItem(connection, record));
            }
            return pageResultFactory.create(items, repository.count(connection, filter), query, null);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用列表当前无法读取");
        }
    }

    public ApplicationDetail detail(RequestContext context, UUID id) {
        RequestPermissions.require(context, Permissions.APPLICATION_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            ApplicationRecord record = load(connection, id);
            requireScope(connection, context, record.code());
            return toDetail(connection, record);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用详情当前无法读取");
        }
    }

    public ApplicationQuotaPolicyView quota(RequestContext context, UUID id) {
        RequestPermissions.require(context, Permissions.APPLICATION_QUOTA_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            ApplicationRecord application = load(connection, id);
            requireScope(connection, context, application.code());
            return repository.findQuota(connection, id).map(this::toQuotaView)
                    .orElseThrow(() -> new LightAiException(
                            ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用缺少额度策略"));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用额度当前无法读取");
        }
    }

    public List<ApplicationModelPermissionView> models(RequestContext context, UUID id) {
        RequestPermissions.require(context, Permissions.APPLICATION_MODEL_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            ApplicationRecord application = load(connection, id);
            requireScope(connection, context, application.code());
            return repository.listModelPermissions(connection, id).stream()
                    .map(this::toModelView).toList();
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用模型权限当前无法读取");
        }
    }

    public ManagementOperationResult<ApplicationDetail> create(
            RequestContext context, ApplicationCreateCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_MANAGE);
        ValidatedCreate value = validateCreate(command);
        UUID id = UUID.randomUUID();
        try {
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                if (repository.existsByCode(connection, value.code())) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                            "应用编码已存在", "code");
                }
                validateModels(connection, value.virtualModelIds());
                ApplicationRecord record = new ApplicationRecord(
                        id, value.code(), value.name(), value.department(), value.ownerId(),
                        value.ownerName(), value.environment(), value.description(), value.status(),
                        null, 1L, null, null);
                repository.insert(connection, record);
                repository.insertOwner(connection, id, value.ownerId(), value.ownerName());
                repository.insertQuota(connection, new ApplicationQuotaRecord(
                        UUID.randomUUID(), id, value.tokenLimit(), value.amountLimit(), value.currency(),
                        value.rpm(), value.tpm(), value.periodType(), value.periodStart(), value.periodEnd(),
                        0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, 1L, null, null));
                for (UUID modelId : value.virtualModelIds()) {
                    repository.insertModelPermission(connection, id, modelId, "{}");
                }
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context), "CREATE",
                        "APPLICATION", id.toString(), List.of(
                                FieldChange.changed("code", null, value.code()),
                                FieldChange.changed("name", null, value.name()),
                                FieldChange.changed("status", null, value.status()),
                                FieldChange.changed("owner_id", null, value.ownerId()),
                                FieldChange.changed("token_limit", null, value.tokenLimit()),
                                FieldChange.changed("amount_limit", null,
                                        decimalText(value.amountLimit())),
                                FieldChange.changed("rpm", null, value.rpm()),
                                FieldChange.changed("tpm", null, value.tpm()),
                                FieldChange.changed("virtual_model_ids", null,
                                        value.virtualModelIds().stream().map(UUID::toString).toList())),
                        sourceMode, context.sourceIpMasked()));
            });
            ApplicationDetail entity = detail(context, id);
            return new ManagementOperationResult<>(id.toString(), entity.version(), entity,
                    false, null, context.requestId());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用创建失败");
        }
    }

    public ManagementOperationResult<ApplicationDetail> update(
            RequestContext context, UUID id, ApplicationUpdateCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_MANAGE);
        ValidatedUpdate value = validateUpdate(command);
        try {
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord current = load(connection, id);
                requireScope(connection, context, current.code());
                if ("ARCHIVED".equals(current.status())) {
                    throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE, "归档应用不能再编辑");
                }
                if (current.version() != value.version()) {
                    throw versionConflict(context, current.version());
                }
                ApplicationRecord requested = new ApplicationRecord(
                        current.id(), current.code(), value.name(), value.department(), value.ownerId(),
                        value.ownerName(), value.environment(), value.description(), current.status(),
                        current.lastCalledAt(), current.version(), current.createdAt(), current.updatedAt());
                repository.update(connection, requested, value.version());
                if (!current.ownerId().equals(value.ownerId())
                        || !current.ownerName().equals(value.ownerName())) {
                    repository.replaceOwner(connection, id, value.ownerId(), value.ownerName());
                }
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context), "UPDATE",
                        "APPLICATION", id.toString(), changedApplicationFields(current, requested),
                        sourceMode, context.sourceIpMasked()));
            });
            ApplicationDetail entity = detail(context, id);
            return new ManagementOperationResult<>(id.toString(), entity.version(), entity,
                    false, null, context.requestId());
        } catch (JdbcApplicationRepository.OptimisticLockException e) {
            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "应用版本已变化，请刷新后重试");
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用更新失败");
        }
    }

    public ManagementOperationResult<ApplicationDetail> changeStatus(
            RequestContext context, UUID id, ApplicationStatusCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_MANAGE);
        String target = optionalEnum(command.status(), STATUSES, "status");
        if (target == null) throw invalid("status", "目标状态必填");
        if (command.reason() == null || command.reason().isBlank() || command.reason().length() > 500) {
            throw invalid("reason", "状态变更原因长度为 1—500 字符");
        }
        try {
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord current = load(connection, id);
                requireScope(connection, context, current.code());
                if (current.version() != command.version()) {
                    throw versionConflict(context, current.version());
                }
                if ("ARCHIVED".equals(current.status())) {
                    throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE, "归档应用是终态");
                }
                if ("ARCHIVED".equals(target) && !"DISABLED".equals(current.status())) {
                    throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                            "应用必须先停用才能归档");
                }
                repository.updateStatus(connection, id, target, command.version());
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context),
                        "APPLICATION_STATUS_CHANGE", "APPLICATION", id.toString(), List.of(
                                FieldChange.changed("status", current.status(), target),
                                FieldChange.changed("reason", null, command.reason().trim())),
                        sourceMode, context.sourceIpMasked()));
            });
            ApplicationDetail entity = detail(context, id);
            return new ManagementOperationResult<>(id.toString(), entity.version(), entity,
                    false, null, context.requestId());
        } catch (JdbcApplicationRepository.OptimisticLockException e) {
            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "应用版本已变化，请刷新后重试");
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用状态更新失败");
        }
    }

    public ManagementOperationResult<ApplicationDetail> updateQuota(
            RequestContext context, UUID id, ApplicationQuotaUpdateCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_QUOTA_MANAGE);
        ValidatedQuota value = validateQuota(command);
        try {
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord application = load(connection, id);
                requireScope(connection, context, application.code());
                requireEditable(application);
                ApplicationQuotaRecord current = repository.findQuota(connection, id)
                        .orElseThrow(() -> new LightAiException(
                                ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用缺少额度策略"));
                if (current.version() != value.version()) {
                    throw versionConflict(context, current.version());
                }
                validateQuotaTransition(current, value);
                ApplicationQuotaRecord requested = new ApplicationQuotaRecord(
                        current.id(), id, value.tokenLimit(), value.amountLimit(), value.currency(),
                        value.rpm(), value.tpm(), value.periodType(), value.periodStart(), value.periodEnd(),
                        current.tokensUsed(), current.tokensReserved(), current.amountUsed(),
                        current.amountReserved(), current.version(), current.createdAt(), current.updatedAt());
                repository.updateQuota(connection, requested, value.version());
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context),
                        "APPLICATION_QUOTA_UPDATE", "APPLICATION", id.toString(),
                        changedQuotaFields(current, requested, value.reason()),
                        sourceMode, context.sourceIpMasked()));
            });
            ApplicationDetail entity = detail(context, id);
            return new ManagementOperationResult<>(id.toString(), entity.quota().version(), entity,
                    false, null, context.requestId());
        } catch (JdbcApplicationRepository.OptimisticLockException e) {
            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "应用额度版本已变化，请刷新后重试");
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用额度更新失败");
        }
    }

    public ManagementOperationResult<ApplicationDetail> updateModels(
            RequestContext context, UUID id, ApplicationModelsUpdateCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_MODEL_MANAGE);
        ValidatedModels value = validateModelsCommand(command);
        try {
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord application = load(connection, id);
                requireScope(connection, context, application.code());
                requireEditable(application);
                if (application.version() != value.applicationVersion()) {
                    throw versionConflict(context, application.version());
                }
                validateModels(connection, value.virtualModelIds());
                List<ApplicationModelPermissionRecord> current =
                        repository.listModelPermissions(connection, id);
                Set<UUID> target = Set.copyOf(value.virtualModelIds());
                Set<UUID> known = new LinkedHashSet<>();
                boolean changed = false;
                for (ApplicationModelPermissionRecord permission : current) {
                    known.add(permission.virtualModelId());
                    boolean enabled = target.contains(permission.virtualModelId());
                    ApplicationModelConstraint constraint = enabled
                            ? value.constraints().get(permission.virtualModelId()) : null;
                    String constraintsJson = enabled
                            ? constraintsJson(constraint) : permission.constraintsJson();
                    if (permission.enabled() != enabled
                            || (enabled && !sameConstraints(permission.constraintsJson(), constraint))) {
                        repository.updateModelPermission(connection, permission.id(), enabled,
                                constraintsJson, permission.version());
                        changed = true;
                    }
                }
                for (UUID modelId : value.virtualModelIds()) {
                    if (!known.contains(modelId)) {
                        repository.insertModelPermission(connection, id, modelId,
                                constraintsJson(value.constraints().get(modelId)));
                        changed = true;
                    }
                }
                if (changed) {
                    repository.bumpApplicationVersion(connection, id, value.applicationVersion());
                    List<String> before = current.stream().filter(ApplicationModelPermissionRecord::enabled)
                            .map(item -> item.virtualModelId().toString()).sorted().toList();
                    List<String> after = value.virtualModelIds().stream()
                            .map(UUID::toString).sorted().toList();
                    List<String> beforeConstraints = current.stream()
                            .filter(ApplicationModelPermissionRecord::enabled)
                            .map(item -> constraintText(item.virtualModelId(), item.constraintsJson()))
                            .sorted().toList();
                    List<String> afterConstraints = value.virtualModelIds().stream()
                            .map(modelId -> constraintText(modelId, constraintsJson(
                                    value.constraints().get(modelId))))
                            .sorted().toList();
                    auditService.recordSuccess(connection, AuditRecord.succeeded(
                            UUID.randomUUID(), context.requestId(), operatorId(context),
                            "APPLICATION_MODEL_PERMISSION_UPDATE", "APPLICATION", id.toString(),
                            List.of(
                                    FieldChange.changed("virtual_model_ids", before, after),
                                    FieldChange.changed("virtual_model_constraints",
                                            beforeConstraints, afterConstraints),
                                    FieldChange.changed("reason", null, value.reason())),
                            sourceMode, context.sourceIpMasked()));
                }
            });
            ApplicationDetail entity = detail(context, id);
            return new ManagementOperationResult<>(id.toString(), entity.version(), entity,
                    false, null, context.requestId());
        } catch (JdbcApplicationRepository.OptimisticLockException e) {
            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "应用版本已变化，请刷新后重试");
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用模型授权更新失败");
        }
    }

    public List<ApplicationQuotaAdjustmentView> listAdjustments(
            RequestContext context, UUID id) {
        RequestPermissions.require(context, Permissions.APPLICATION_QUOTA_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            ApplicationRecord application = load(connection, id);
            requireScope(connection, context, application.code());
            return repository.listAdjustments(connection, id, 100).stream()
                    .map(this::toAdjustmentView).toList();
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE,
                    "应用额度调整流水当前无法读取");
        }
    }

    /**
     * 应用成员只读列表（PRD 9.2.7）。成员变更方式在 PRD 中仍属待确认事项，
     * 因此本期只提供查看，不提供平台侧增删改。
     */
    public List<ApplicationMemberView> listMembers(RequestContext context, UUID id) {
        RequestPermissions.require(context, Permissions.APPLICATION_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            ApplicationRecord application = load(connection, id);
            requireScope(connection, context, application.code());
            return repository.listMembers(connection, id).stream()
                    .map(member -> new ApplicationMemberView(
                            member.id().toString(), member.subjectId(),
                            member.subjectName(), member.role()))
                    .toList();
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用成员当前无法读取");
        }
    }

    public ManagementOperationResult<ApplicationDetail> adjustQuota(
            RequestContext context, UUID id, ApplicationQuotaAdjustmentCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_QUOTA_MANAGE);
        ValidatedAdjustment value = validateAdjustment(command);
        try {
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord application = load(connection, id);
                requireScope(connection, context, application.code());
                requireEditable(application);
                ApplicationQuotaRecord current = repository.lockQuota(connection, id);
                var previous = repository.findAdjustment(connection, id, value.idempotencyKey());
                if (previous.isPresent()) {
                    requireSameAdjustment(previous.get(), value);
                    return;
                }
                if (current.version() != value.quotaVersion()) {
                    throw versionConflict(context, current.version());
                }
                BigDecimal before = adjustmentBefore(current, value.dimension());
                if (before == null) {
                    throw invalid("dimension", "不限额策略不能使用增量调整，请先设置明确上限");
                }
                BigDecimal after = before.add(value.delta());
                validateAdjustedLimit(current, value.dimension(), after);
                ApplicationQuotaRecord requested = adjustedQuota(current, value.dimension(), after);
                repository.updateQuota(connection, requested, current.version());
                OffsetDateTime effectiveAt = OffsetDateTime.now(clock);
                repository.insertAdjustment(connection, new QuotaAdjustmentRecord(
                        UUID.randomUUID(), id, value.dimension(), before, value.delta(), after,
                        value.reason(), effectiveAt, operatorId(context), value.idempotencyKey(), null));
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context),
                        "APPLICATION_QUOTA_ADJUST", "APPLICATION", id.toString(), List.of(
                                FieldChange.changed("dimension", null, value.dimension()),
                                FieldChange.changed("limit", decimalText(before), decimalText(after)),
                                FieldChange.changed("delta", null, decimalText(value.delta())),
                                FieldChange.changed("reason", null, value.reason()),
                                FieldChange.changed("idempotency_key", null, value.idempotencyKey())),
                        sourceMode, context.sourceIpMasked()));
            });
            ApplicationDetail entity = detail(context, id);
            return new ManagementOperationResult<>(id.toString(), entity.quota().version(), entity,
                    false, null, context.requestId());
        } catch (JdbcApplicationRepository.OptimisticLockException e) {
            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "应用额度版本已变化，请刷新后重试");
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用额度调整失败");
        }
    }

    public ManagementOperationResult<ApplicationDetail> resetQuotaUsage(
            RequestContext context, UUID id, ApplicationQuotaResetCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_QUOTA_MANAGE);
        ValidatedReset value = validateReset(command);
        try {
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord application = load(connection, id);
                requireScope(connection, context, application.code());
                requireEditable(application);
                if (!application.code().equals(value.confirmationCode())) {
                    throw invalid("confirmation_code", "确认文本必须与应用编码完全一致");
                }
                ApplicationQuotaRecord current = repository.lockQuota(connection, id);
                var previous = repository.findAdjustment(connection, id, value.idempotencyKey());
                if (previous.isPresent()) {
                    requireSameReset(previous.get(), value);
                    return;
                }
                if (current.version() != value.quotaVersion()) {
                    throw versionConflict(context, current.version());
                }
                BigDecimal before = resetBefore(current, value.dimension());
                if (before.signum() == 0) {
                    throw invalid("dimension", "当前维度的已用量为 0，无需重置");
                }
                repository.resetUsage(connection, id,
                        "TOKEN_USAGE".equals(value.dimension()), current.version());
                String ledgerDimension = resetLedgerDimension(value.dimension());
                OffsetDateTime effectiveAt = OffsetDateTime.now(clock);
                repository.insertAdjustment(connection, new QuotaAdjustmentRecord(
                        UUID.randomUUID(), id, ledgerDimension, before, before.negate(),
                        BigDecimal.ZERO, value.reason(), effectiveAt, operatorId(context),
                        value.idempotencyKey(), null));
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context),
                        "APPLICATION_QUOTA_USAGE_RESET", "APPLICATION", id.toString(), List.of(
                                FieldChange.changed("dimension", null, ledgerDimension),
                                FieldChange.changed("usage", decimalText(before), "0"),
                                FieldChange.changed("reason", null, value.reason()),
                                FieldChange.changed("idempotency_key", null, value.idempotencyKey())),
                        sourceMode, context.sourceIpMasked()));
            });
            ApplicationDetail entity = detail(context, id);
            return new ManagementOperationResult<>(id.toString(), entity.quota().version(), entity,
                    false, null, context.requestId());
        } catch (JdbcApplicationRepository.OptimisticLockException e) {
            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "应用额度版本已变化，请刷新后重试");
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用用量重置失败");
        }
    }

    private ApplicationListItem toListItem(Connection connection, ApplicationRecord record) {
        ApplicationQuotaRecord quota = repository.findQuota(connection, record.id()).orElse(null);
        return new ApplicationListItem(
                record.id().toString(), record.code(), record.name(), record.department(),
                record.ownerId(), record.ownerName(), record.environment(), record.status(),
                repository.countEnabledModels(connection, record.id()),
                repository.countActiveKeys(connection, record.id()),
                quota == null ? null : quota.tokenLimit(), quota == null ? 0 : quota.tokensUsed(),
                quota == null ? 0 : quota.tokensReserved(),
                quota == null ? null : decimalText(quota.amountLimit()),
                quota == null ? "0" : decimalText(quota.amountUsed()),
                quota == null ? "0" : decimalText(quota.amountReserved()),
                quota == null ? null : quota.currency(), quota == null ? null : quota.rpm(),
                quota == null ? null : quota.tpm(), record.lastCalledAt(), record.updatedAt(), record.version());
    }

    private ApplicationDetail toDetail(Connection connection, ApplicationRecord record) {
        ApplicationQuotaPolicyView quota = repository.findQuota(connection, record.id())
                .map(this::toQuotaView).orElse(null);
        List<ApplicationModelPermissionView> models = repository.listModelPermissions(connection, record.id())
                .stream().map(this::toModelView).toList();
        return new ApplicationDetail(
                record.id().toString(), record.code(), record.name(), record.department(),
                record.ownerId(), record.ownerName(), record.environment(), record.description(),
                record.status(), repository.countActiveKeys(connection, record.id()), quota, models,
                record.lastCalledAt(), record.createdAt(), record.updatedAt(), record.version());
    }

    private ApplicationQuotaPolicyView toQuotaView(ApplicationQuotaRecord quota) {
        return new ApplicationQuotaPolicyView(
                quota.id().toString(), quota.tokenLimit(), quota.tokensUsed(), quota.tokensReserved(),
                decimalText(quota.amountLimit()), decimalText(quota.amountUsed()),
                decimalText(quota.amountReserved()), quota.currency(), quota.rpm(), quota.tpm(),
                quota.periodType(), quota.periodStart(), quota.periodEnd(), quota.version());
    }

    private ApplicationModelPermissionView toModelView(ApplicationModelPermissionRecord model) {
        ApplicationModelConstraint constraint =
                ApplicationModelConstraint.fromJson(model.virtualModelCode(), model.constraintsJson());
        return new ApplicationModelPermissionView(
                model.id().toString(), model.virtualModelId().toString(),
                model.virtualModelCode(), model.enabled(),
                constraint.maxOutputTokens(), constraint.streamAllowed(), model.version());
    }

    private ApplicationQuotaAdjustmentView toAdjustmentView(QuotaAdjustmentRecord record) {
        return new ApplicationQuotaAdjustmentView(
                record.id().toString(), record.applicationId().toString(), record.dimension(),
                decimalText(record.beforeValue()), decimalText(record.deltaValue()),
                decimalText(record.afterValue()), record.reason(), record.effectiveAt(),
                record.operatorId(), record.createdAt());
    }

    private ApplicationRecord load(Connection connection, UUID id) {
        return repository.findById(connection, id)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "应用不存在"));
    }

    private void validateModels(Connection connection, List<UUID> ids) {
        for (UUID id : ids) {
            var model = aliasRepository.findLiveById(connection, id)
                    .orElseThrow(() -> invalid("virtual_model_ids", "虚拟模型不存在: " + id));
            if (!model.enabled()) {
                throw invalid("virtual_model_ids", "虚拟模型未启用: " + model.alias());
            }
        }
    }

    private void requireEditable(ApplicationRecord application) {
        if ("ARCHIVED".equals(application.status())) {
            throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE, "归档应用不能再编辑");
        }
    }

    private ValidatedQuota validateQuota(ApplicationQuotaUpdateCommand command) {
        if (command == null) throw invalid("body", "请求体必填");
        List<FieldIssue> issues = new ArrayList<>();
        Long tokenLimit = positive(command.tokenLimit(), "token_limit", issues);
        BigDecimal amountLimit = amount(command.amountLimit(), issues);
        String currency = enumPattern(command.currency(), CURRENCY, "currency", issues);
        Integer rpm = positive(command.rpm(), "rpm", issues);
        Long tpm = positive(command.tpm(), "tpm", issues);
        String periodType = enumValue(command.periodType(), PERIOD_TYPES, "period_type", issues);
        if (command.version() < 1) {
            issues.add(new FieldIssue("version", "INVALID", "version 必须是正整数"));
        }
        String reason = reason(command.reason(), issues);
        if ("CUSTOM".equals(periodType) && (command.periodStart() == null
                || command.periodEnd() == null
                || !command.periodStart().isBefore(command.periodEnd()))) {
            issues.add(new FieldIssue("period_end", "INVALID", "自定义周期必须提供有效起止时间"));
        }
        if (!issues.isEmpty()) throw new LightAiException(
                ErrorCode.FIELD_VALIDATION_FAILED, "应用额度配置不合法", issues);
        return new ValidatedQuota(tokenLimit, amountLimit, currency, rpm, tpm,
                periodType, command.periodStart(), command.periodEnd(), command.version(), reason);
    }

    private ValidatedModels validateModelsCommand(ApplicationModelsUpdateCommand command) {
        if (command == null) throw invalid("body", "请求体必填");
        List<FieldIssue> issues = new ArrayList<>();
        List<UUID> modelIds = uuidList(command.virtualModelIds(), issues);
        Map<UUID, ApplicationModelConstraint> constraints =
                modelConstraints(command.constraints(), modelIds, issues);
        if (command.applicationVersion() < 1) {
            issues.add(new FieldIssue("application_version", "INVALID",
                    "application_version 必须是正整数"));
        }
        String reason = reason(command.reason(), issues);
        if (!issues.isEmpty()) throw new LightAiException(
                ErrorCode.FIELD_VALIDATION_FAILED, "应用模型授权不合法", issues);
        return new ValidatedModels(modelIds, constraints, command.applicationVersion(), reason);
    }

    /**
     * 解析应用级模型参数上限：只能对本次授权的模型配置，且上限必须是正整数。
     * 两个维度都为空的条目按“无上限”处理，不进入持久化。
     */
    private static Map<UUID, ApplicationModelConstraint> modelConstraints(
            List<ApplicationModelConstraint> raw, List<UUID> granted, List<FieldIssue> issues) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Set<UUID> grantedIds = new LinkedHashSet<>(granted);
        Map<UUID, ApplicationModelConstraint> result = new LinkedHashMap<>();
        for (ApplicationModelConstraint constraint : raw) {
            if (constraint == null) continue;
            UUID modelId;
            try {
                modelId = UUID.fromString(constraint.virtualModelId() == null
                        ? "" : constraint.virtualModelId());
            } catch (Exception e) {
                issues.add(new FieldIssue("constraints[].virtual_model_id", "INVALID",
                        "包含非法虚拟模型 ID"));
                continue;
            }
            if (!grantedIds.contains(modelId)) {
                issues.add(new FieldIssue("constraints[].virtual_model_id", "NOT_GRANTED",
                        "模型 " + modelId + " 未在本次授权范围内，不能配置参数上限"));
                continue;
            }
            if (constraint.isEmpty()) {
                continue;
            }
            if (constraint.maxOutputTokens() != null && constraint.maxOutputTokens() < 1) {
                issues.add(new FieldIssue("constraints[].max_output_tokens", "INVALID",
                        "max_output_tokens 必须是正整数"));
                continue;
            }
            result.put(modelId, constraint);
        }
        return Map.copyOf(result);
    }

    private static String constraintsJson(ApplicationModelConstraint constraint) {
        return constraint == null ? "{}" : constraint.toJson();
    }

    private static boolean sameConstraints(String storedJson, ApplicationModelConstraint target) {
        ApplicationModelConstraint stored = ApplicationModelConstraint.fromJson(null, storedJson);
        ApplicationModelConstraint next = target == null
                ? new ApplicationModelConstraint(null, null, null) : target;
        return Objects.equals(stored.maxOutputTokens(), next.maxOutputTokens())
                && Objects.equals(stored.streamAllowed(), next.streamAllowed());
    }

    private static String constraintText(UUID modelId, String constraintsJson) {
        ApplicationModelConstraint constraint = ApplicationModelConstraint.fromJson(null, constraintsJson);
        if (constraint.isEmpty()) {
            return modelId + "=unlimited";
        }
        return modelId + "=max_output_tokens:" + constraint.maxOutputTokens()
                + ",stream_allowed:" + constraint.streamAllowed();
    }

    private ValidatedAdjustment validateAdjustment(ApplicationQuotaAdjustmentCommand command) {
        if (command == null) throw invalid("body", "请求体必填");
        List<FieldIssue> issues = new ArrayList<>();
        String dimension = enumValue(command.dimension(),
                Set.of("TOKEN_LIMIT", "AMOUNT_LIMIT"), "dimension", issues);
        BigDecimal delta = adjustmentDelta(command.delta(), dimension, issues);
        String reason = reason(command.reason(), issues);
        String idempotencyKey = validateIdempotencyKey(command.idempotencyKey(), issues);
        if (command.quotaVersion() < 1) {
            issues.add(new FieldIssue("quota_version", "INVALID", "quota_version 必须是正整数"));
        }
        if (!issues.isEmpty()) throw new LightAiException(
                ErrorCode.FIELD_VALIDATION_FAILED, "应用额度调整不合法", issues);
        return new ValidatedAdjustment(dimension, delta, reason,
                idempotencyKey, command.quotaVersion());
    }

    private ValidatedReset validateReset(ApplicationQuotaResetCommand command) {
        if (command == null) throw invalid("body", "请求体必填");
        List<FieldIssue> issues = new ArrayList<>();
        String dimension = enumValue(command.dimension(),
                Set.of("TOKEN_USAGE", "AMOUNT_USAGE"), "dimension", issues);
        String reason = reason(command.reason(), issues);
        String confirmationCode = trimToNull(command.confirmationCode());
        if (confirmationCode == null) {
            issues.add(new FieldIssue("confirmation_code", "REQUIRED", "请输入应用编码进行确认"));
        }
        String idempotencyKey = validateIdempotencyKey(command.idempotencyKey(), issues);
        if (command.quotaVersion() < 1) {
            issues.add(new FieldIssue("quota_version", "INVALID", "quota_version 必须是正整数"));
        }
        if (!issues.isEmpty()) throw new LightAiException(
                ErrorCode.FIELD_VALIDATION_FAILED, "应用用量重置不合法", issues);
        return new ValidatedReset(dimension, reason, confirmationCode,
                idempotencyKey, command.quotaVersion());
    }

    private static String validateIdempotencyKey(String raw, List<FieldIssue> issues) {
        String idempotencyKey = trimToNull(raw);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            issues.add(new FieldIssue("idempotency_key", "INVALID",
                    "幂等键使用 1—128 位字母、数字、点号、下划线、冒号或连字符"));
        }
        return idempotencyKey;
    }

    private static BigDecimal adjustmentDelta(
            String raw, String dimension, List<FieldIssue> issues) {
        try {
            BigDecimal value = new BigDecimal(raw == null ? "" : raw.trim());
            value = "TOKEN_LIMIT".equals(dimension)
                    ? value.setScale(0, RoundingMode.UNNECESSARY)
                    : value.setScale(8, RoundingMode.UNNECESSARY);
            if (value.signum() == 0) throw new ArithmeticException();
            if ("TOKEN_LIMIT".equals(dimension)) value.longValueExact();
            return value;
        } catch (Exception e) {
            issues.add(new FieldIssue("delta", "INVALID",
                    "Token 调整必须是非零整数，金额调整必须是最多 8 位小数的非零数值"));
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal adjustmentBefore(
            ApplicationQuotaRecord current, String dimension) {
        return "TOKEN_LIMIT".equals(dimension)
                ? current.tokenLimit() == null ? null : BigDecimal.valueOf(current.tokenLimit())
                : current.amountLimit();
    }

    private static void validateAdjustedLimit(
            ApplicationQuotaRecord current, String dimension, BigDecimal after) {
        if (after.signum() <= 0) {
            throw invalid("delta", "调整后上限必须大于 0");
        }
        if ("TOKEN_LIMIT".equals(dimension)) {
            long committed = current.tokensUsed() + current.tokensReserved();
            if (after.longValueExact() < committed) {
                throw invalid("delta", "调整后 Token 额度不能低于已用与预占之和");
            }
        } else if (after.compareTo(current.amountUsed().add(current.amountReserved())) < 0) {
            throw invalid("delta", "调整后金额预算不能低于已用与预占之和");
        }
    }

    private static ApplicationQuotaRecord adjustedQuota(
            ApplicationQuotaRecord current, String dimension, BigDecimal after) {
        Long tokenLimit = current.tokenLimit();
        BigDecimal amountLimit = current.amountLimit();
        if ("TOKEN_LIMIT".equals(dimension)) tokenLimit = after.longValueExact();
        else amountLimit = after;
        return new ApplicationQuotaRecord(
                current.id(), current.applicationId(), tokenLimit, amountLimit,
                current.currency(), current.rpm(), current.tpm(), current.periodType(),
                current.periodStart(), current.periodEnd(), current.tokensUsed(),
                current.tokensReserved(), current.amountUsed(), current.amountReserved(),
                current.version(), current.createdAt(), current.updatedAt());
    }

    private static void requireSameAdjustment(
            QuotaAdjustmentRecord previous, ValidatedAdjustment requested) {
        if (!previous.dimension().equals(requested.dimension())
                || previous.deltaValue().compareTo(requested.delta()) != 0
                || !previous.reason().equals(requested.reason())) {
            throw new LightAiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "幂等键已用于不同的额度调整");
        }
    }

    private static BigDecimal resetBefore(ApplicationQuotaRecord current, String dimension) {
        return "TOKEN_USAGE".equals(dimension)
                ? BigDecimal.valueOf(current.tokensUsed()) : current.amountUsed();
    }

    private static String resetLedgerDimension(String dimension) {
        return "TOKEN_USAGE".equals(dimension) ? "TOKEN_USAGE_RESET" : "AMOUNT_USAGE_RESET";
    }

    private static void requireSameReset(
            QuotaAdjustmentRecord previous, ValidatedReset requested) {
        if (!previous.dimension().equals(resetLedgerDimension(requested.dimension()))
                || !previous.reason().equals(requested.reason())) {
            throw new LightAiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "幂等键已用于不同的额度操作");
        }
    }

    private void validateQuotaTransition(ApplicationQuotaRecord current, ValidatedQuota requested) {
        long committedTokens = current.tokensUsed() + current.tokensReserved();
        if (requested.tokenLimit() != null && requested.tokenLimit() < committedTokens) {
            throw invalid("token_limit", "Token 额度不能低于已用与预占之和");
        }
        BigDecimal committedAmount = current.amountUsed().add(current.amountReserved());
        if (requested.amountLimit() != null
                && requested.amountLimit().compareTo(committedAmount) < 0) {
            throw invalid("amount_limit", "金额预算不能低于已用与预占之和");
        }
        if (committedAmount.signum() > 0
                && !current.currency().equals(requested.currency())) {
            throw invalid("currency", "本周期已有金额用量，不能变更币种");
        }
        if ((current.tokensUsed() > 0 || current.tokensReserved() > 0
                || committedAmount.signum() > 0)
                && (!current.periodType().equals(requested.periodType())
                || !java.util.Objects.equals(current.periodStart(), requested.periodStart())
                || !java.util.Objects.equals(current.periodEnd(), requested.periodEnd()))) {
            throw invalid("period_type", "当前周期已有用量，不能直接变更周期");
        }
    }

    private static List<FieldChange> changedQuotaFields(
            ApplicationQuotaRecord before, ApplicationQuotaRecord after, String reason) {
        List<FieldChange> changes = new ArrayList<>();
        addChange(changes, "token_limit", before.tokenLimit(), after.tokenLimit());
        addChange(changes, "amount_limit", decimalText(before.amountLimit()),
                decimalText(after.amountLimit()));
        addChange(changes, "currency", before.currency(), after.currency());
        addChange(changes, "rpm", before.rpm(), after.rpm());
        addChange(changes, "tpm", before.tpm(), after.tpm());
        addChange(changes, "period_type", before.periodType(), after.periodType());
        addChange(changes, "period_start", before.periodStart(), after.periodStart());
        addChange(changes, "period_end", before.periodEnd(), after.periodEnd());
        changes.add(FieldChange.changed("reason", null, reason));
        return List.copyOf(changes);
    }

    private List<String> effectiveScope(Connection connection, RequestContext context) {
        List<String> roles = context.authContext().roles();
        List<String> declared = context.authContext().applicationScope();
        if (roles.contains(Roles.SYSTEM_ADMIN) || roles.contains(Roles.OPERATOR)
                || declared.contains("*")) {
            return List.of();
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>(declared);
        String userId = context.authContext().userId();
        if (userId != null && !userId.isBlank()) {
            codes.addAll(repository.findCodesForSubject(connection, userId));
        }
        return codes.isEmpty() ? List.of(NO_APPLICATION) : List.copyOf(codes);
    }

    private void requireScope(Connection connection, RequestContext context, String code) {
        List<String> scope = effectiveScope(connection, context);
        if (!scope.isEmpty() && !scope.contains(code)) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权访问该应用");
        }
    }

    private ValidatedCreate validateCreate(ApplicationCreateCommand command) {
        if (command == null) throw invalid("body", "请求体必填");
        List<FieldIssue> issues = new ArrayList<>();
        String code = required(command.code(), "code", 64, issues);
        if (code != null && !CODE.matcher(code).matches()) {
            issues.add(new FieldIssue("code", "INVALID", "编码使用小写字母、数字和连字符"));
        }
        String name = required(command.name(), "name", 128, issues);
        String ownerId = required(command.ownerId(), "owner_id", 128, issues);
        String ownerName = required(command.ownerName(), "owner_name", 128, issues);
        String environment = enumValue(command.environment(), ENVIRONMENTS, "environment", issues);
        String status = command.status() == null || command.status().isBlank()
                ? "ACTIVE" : enumValue(command.status(), CREATE_STATUSES, "status", issues);
        String currency = enumPattern(command.currency(), CURRENCY, "currency", issues);
        String period = enumValue(command.periodType(), PERIOD_TYPES, "period_type", issues);
        Long tokenLimit = positive(command.tokenLimit(), "token_limit", issues);
        BigDecimal amount = amount(command.amountLimit(), issues);
        Integer rpm = positive(command.rpm(), "rpm", issues);
        Long tpm = positive(command.tpm(), "tpm", issues);
        String department = optional(command.department(), 128, "department", issues);
        String description = optional(command.description(), 1000, "description", issues);
        if ("CUSTOM".equals(period) && (command.periodStart() == null || command.periodEnd() == null
                || !command.periodStart().isBefore(command.periodEnd()))) {
            issues.add(new FieldIssue("period_end", "INVALID", "自定义周期必须提供有效起止时间"));
        }
        List<UUID> modelIds = uuidList(command.virtualModelIds(), issues);
        if (!issues.isEmpty()) throw new LightAiException(
                ErrorCode.FIELD_VALIDATION_FAILED, "应用配置不合法", issues);
        return new ValidatedCreate(code, name, department, ownerId, ownerName, environment,
                description, status, tokenLimit, amount, currency, rpm, tpm, period,
                command.periodStart(), command.periodEnd(), modelIds);
    }

    private ValidatedUpdate validateUpdate(ApplicationUpdateCommand command) {
        if (command == null) throw invalid("body", "请求体必填");
        List<FieldIssue> issues = new ArrayList<>();
        String name = required(command.name(), "name", 128, issues);
        String ownerId = required(command.ownerId(), "owner_id", 128, issues);
        String ownerName = required(command.ownerName(), "owner_name", 128, issues);
        String environment = enumValue(command.environment(), ENVIRONMENTS, "environment", issues);
        String department = optional(command.department(), 128, "department", issues);
        String description = optional(command.description(), 1000, "description", issues);
        if (command.version() < 1) {
            issues.add(new FieldIssue("version", "INVALID", "version 必须是正整数"));
        }
        if (!issues.isEmpty()) throw new LightAiException(
                ErrorCode.FIELD_VALIDATION_FAILED, "应用配置不合法", issues);
        return new ValidatedUpdate(name, department, ownerId, ownerName,
                environment, description, command.version());
    }

    private static List<FieldChange> changedApplicationFields(
            ApplicationRecord before, ApplicationRecord after) {
        List<FieldChange> changes = new ArrayList<>();
        addChange(changes, "name", before.name(), after.name());
        addChange(changes, "department", before.department(), after.department());
        addChange(changes, "owner_id", before.ownerId(), after.ownerId());
        addChange(changes, "owner_name", before.ownerName(), after.ownerName());
        addChange(changes, "environment", before.environment(), after.environment());
        addChange(changes, "description", before.description(), after.description());
        return List.copyOf(changes);
    }

    private static void addChange(List<FieldChange> changes, String path, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            changes.add(FieldChange.changed(path, before, after));
        }
    }

    private static String operatorId(RequestContext context) {
        String userId = context.authContext().userId();
        return userId == null || userId.isBlank() ? "system" : userId;
    }

    private static LightAiException versionConflict(RequestContext context, long currentVersion) {
        return new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                "应用版本已变化，请刷新后重试", null, context.requestId(),
                null, currentVersion, null);
    }

    private static String optionalEnum(String raw, Set<String> values, String field) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toUpperCase();
        if (!values.contains(value)) throw invalid(field, field + " 取值不支持");
        return value;
    }

    private static String required(String raw, String field, int max, List<FieldIssue> issues) {
        String value = trimToNull(raw);
        if (value == null) {
            issues.add(new FieldIssue(field, "REQUIRED", field + " 必填"));
        } else if (value.length() > max) {
            issues.add(new FieldIssue(field, "TOO_LONG", field + " 最长 " + max + " 字符"));
        }
        return value;
    }

    private static String optional(String raw, int max, String field, List<FieldIssue> issues) {
        String value = trimToNull(raw);
        if (value != null && value.length() > max) {
            issues.add(new FieldIssue(field, "TOO_LONG", field + " 最长 " + max + " 字符"));
        }
        return value;
    }

    private static String reason(String raw, List<FieldIssue> issues) {
        String value = trimToNull(raw);
        if (value == null) {
            issues.add(new FieldIssue("reason", "REQUIRED", "变更原因必填"));
        } else if (value.length() > 500) {
            issues.add(new FieldIssue("reason", "TOO_LONG", "变更原因最长 500 字符"));
        }
        return value;
    }

    private static String enumValue(String raw, Set<String> values, String field,
                                    List<FieldIssue> issues) {
        String value = trimToNull(raw);
        if (value == null) {
            issues.add(new FieldIssue(field, "REQUIRED", field + " 必填"));
            return null;
        }
        value = value.toUpperCase();
        if (!values.contains(value)) {
            issues.add(new FieldIssue(field, "INVALID", field + " 取值不支持"));
        }
        return value;
    }

    private static String enumPattern(String raw, Pattern pattern, String field,
                                      List<FieldIssue> issues) {
        String value = trimToNull(raw);
        if (value == null || !pattern.matcher(value.toUpperCase()).matches()) {
            issues.add(new FieldIssue(field, "INVALID", field + " 必须是三位币种代码"));
            return value;
        }
        return value.toUpperCase();
    }

    private static Long positive(Long value, String field, List<FieldIssue> issues) {
        if (value != null && value <= 0) issues.add(new FieldIssue(field, "INVALID", field + " 必须大于 0"));
        return value;
    }

    private static Integer positive(Integer value, String field, List<FieldIssue> issues) {
        if (value != null && value <= 0) issues.add(new FieldIssue(field, "INVALID", field + " 必须大于 0"));
        return value;
    }

    private static BigDecimal amount(String raw, List<FieldIssue> issues) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal value = new BigDecimal(raw.trim()).setScale(8, RoundingMode.UNNECESSARY);
            if (value.signum() <= 0) throw new ArithmeticException();
            return value;
        } catch (Exception e) {
            issues.add(new FieldIssue("amount_limit", "INVALID", "金额上限必须是正数且最多 8 位小数"));
            return null;
        }
    }

    private static List<UUID> uuidList(List<String> values, List<FieldIssue> issues) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        if (values == null) return List.of();
        for (String value : values) {
            try {
                ids.add(UUID.fromString(value));
            } catch (Exception e) {
                issues.add(new FieldIssue("virtual_model_ids", "INVALID", "包含非法虚拟模型 ID"));
            }
        }
        return List.copyOf(ids);
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static LightAiException invalid(String field, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, message, field);
    }

    private record ValidatedCreate(
            String code, String name, String department, String ownerId, String ownerName,
            String environment, String description, String status, Long tokenLimit,
            BigDecimal amountLimit, String currency, Integer rpm, Long tpm, String periodType,
            OffsetDateTime periodStart, OffsetDateTime periodEnd, List<UUID> virtualModelIds) {
    }

    private record ValidatedUpdate(
            String name, String department, String ownerId, String ownerName,
            String environment, String description, long version) {
    }

    private record ValidatedQuota(
            Long tokenLimit, BigDecimal amountLimit, String currency, Integer rpm, Long tpm,
            String periodType, OffsetDateTime periodStart, OffsetDateTime periodEnd,
            long version, String reason) {
    }

    private record ValidatedModels(
            List<UUID> virtualModelIds,
            Map<UUID, ApplicationModelConstraint> constraints,
            long applicationVersion, String reason) {
    }

    private record ValidatedAdjustment(
            String dimension, BigDecimal delta, String reason,
            String idempotencyKey, long quotaVersion) {
    }

    private record ValidatedReset(
            String dimension, String reason, String confirmationCode,
            String idempotencyKey, long quotaVersion) {
    }
}
