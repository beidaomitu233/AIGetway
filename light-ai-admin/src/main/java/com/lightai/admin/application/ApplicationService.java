package com.lightai.admin.application;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.application.ApplicationDetail;
import com.lightai.client.application.ApplicationListItem;
import com.lightai.client.application.ApplicationModelPermissionView;
import com.lightai.client.application.ApplicationQuotaPolicyView;
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
import com.lightai.storage.audit.AuditRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
                    repository.insertModelPermission(connection, id, modelId);
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
        return new ApplicationModelPermissionView(
                model.id().toString(), model.virtualModelId().toString(),
                model.virtualModelCode(), model.enabled(), model.version());
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
}
