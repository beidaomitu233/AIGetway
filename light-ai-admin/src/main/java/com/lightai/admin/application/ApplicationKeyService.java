package com.lightai.admin.application;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.security.AccessTokenService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.application.ApplicationKeyCreateCommand;
import com.lightai.client.application.ApplicationKeyRevokeCommand;
import com.lightai.client.application.ApplicationKeyRotateCommand;
import com.lightai.client.application.ApplicationKeyStatusCommand;
import com.lightai.client.application.ApplicationKeySecretResult;
import com.lightai.client.application.ApplicationKeyView;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.protocol.Roles;
import com.lightai.storage.application.ApplicationKeyRecord;
import com.lightai.storage.application.ApplicationQuotaRecord;
import com.lightai.storage.application.ApplicationRecord;
import com.lightai.storage.application.JdbcApplicationKeyRepository;
import com.lightai.storage.application.JdbcApplicationRepository;
import com.lightai.storage.audit.AuditRecord;
import java.net.InetAddress;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 应用密钥即时生命周期；原文只在签发或轮换响应出现一次。 */
public final class ApplicationKeyService {

    private final DataSource dataSource;
    private final JdbcApplicationRepository applications;
    private final JdbcApplicationKeyRepository keys;
    private final AccessTokenService tokens;
    private final AuditService auditService;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String sourceMode;

    public ApplicationKeyService(
            DataSource dataSource, JdbcApplicationRepository applications,
            JdbcApplicationKeyRepository keys, AccessTokenService tokens,
            AuditService auditService, PlatformTransactionManager transactionManager,
            Clock clock, String sourceMode) {
        this.dataSource = dataSource;
        this.applications = applications;
        this.keys = keys;
        this.tokens = tokens;
        this.auditService = auditService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.sourceMode = sourceMode;
    }

    public List<ApplicationKeyView> list(RequestContext context, UUID applicationId) {
        RequestPermissions.require(context, Permissions.APPLICATION_KEY_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            ApplicationRecord application = loadApplication(connection, applicationId);
            requireScope(connection, context, application.code());
            OffsetDateTime now = OffsetDateTime.now(clock);
            return keys.list(connection, applicationId).stream()
                    .map(key -> view(key, now)).toList();
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用密钥当前无法读取");
        }
    }

    public ApplicationKeySecretResult create(
            RequestContext context, UUID applicationId, ApplicationKeyCreateCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_KEY_MANAGE);
        ValidatedCreate value = validate(command);
        AccessTokenService.Issued issued = tokens.issue();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord application = loadApplication(connection, applicationId);
                requireScope(connection, context, application.code());
                if (!"ACTIVE".equals(application.status())) {
                    throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                            "仅启用中的应用可以签发密钥");
                }
                if (keys.existsName(connection, applicationId, value.name())) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                            "同一应用下密钥名称已存在", "name");
                }
                validateStricterLimits(connection, applicationId, value.rpm(), value.tpm());
                keys.insert(connection, new ApplicationKeyRecord(
                        id, applicationId, value.name(), issued.prefix(), issued.maskedValue(),
                        issued.tokenHash(), issued.pepperVersion(), 1L, value.ipAllowlist(),
                        value.expiresAt(), value.rpm(), value.tpm(), "ACTIVE", null, null,
                        null, null, 1L, now, now));
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context), "CREATE",
                        "APPLICATION_KEY", id.toString(), List.of(
                                FieldChange.changed("application_id", null, applicationId.toString()),
                                FieldChange.changed("name", null, value.name()),
                                FieldChange.sensitiveChanged("key_value")),
                        sourceMode, context.sourceIpMasked()));
            });
            return new ApplicationKeySecretResult(id.toString(), applicationId.toString(), issued.tokenValue(),
                    issued.maskedValue(), now, 1L, 1L);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用密钥签发失败");
        }
    }

    public ApplicationKeySecretResult rotate(
            RequestContext context, UUID applicationId, UUID keyId,
            ApplicationKeyRotateCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_KEY_MANAGE);
        requireReason(command == null ? null : command.reason());
        if (command.version() < 1) throw invalid("version", "version 必须是正整数");
        AccessTokenService.Issued issued = tokens.issue();
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            final ApplicationKeyRecord[] updated = new ApplicationKeyRecord[1];
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord application = loadApplication(connection, applicationId);
                requireScope(connection, context, application.code());
                ApplicationKeyRecord current = loadKey(connection, applicationId, keyId);
                if ("REVOKED".equals(current.status())) {
                    throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                            "已撤销密钥不能轮换，请签发新密钥");
                }
                if (!current.active(now)) {
                    throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                            "仅当前有效且启用的密钥可以轮换");
                }
                updated[0] = keys.replaceSecret(connection, keyId, issued.prefix(),
                        issued.maskedValue(), issued.tokenHash(), issued.pepperVersion(),
                        now, command.version());
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context), "ROTATE",
                        "APPLICATION_KEY", keyId.toString(), List.of(
                                FieldChange.sensitiveChanged("key_value"),
                                FieldChange.changed("reason", null, command.reason().trim())),
                        sourceMode, context.sourceIpMasked()));
            });
            return new ApplicationKeySecretResult(keyId.toString(), applicationId.toString(), issued.tokenValue(),
                    issued.maskedValue(), now, updated[0].rotationGeneration(), updated[0].version());
        } catch (JdbcApplicationKeyRepository.OptimisticLockException e) {
            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "应用密钥版本已变化，请刷新后重试");
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用密钥轮换失败");
        }
    }

    public ManagementOperationResult<ApplicationKeyView> changeStatus(
            RequestContext context, UUID applicationId, UUID keyId,
            ApplicationKeyStatusCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_KEY_MANAGE);
        String target = validateStatus(command);
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            final ApplicationKeyRecord[] updated = new ApplicationKeyRecord[1];
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord application = loadApplication(connection, applicationId);
                requireScope(connection, context, application.code());
                ApplicationKeyRecord current = loadKey(connection, applicationId, keyId);
                if (current.version() != command.version()) {
                    throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                            "应用密钥版本已变化，请刷新后重试");
                }
                if ("REVOKED".equals(current.status())) {
                    throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                            "已撤销密钥不可恢复");
                }
                if ("ACTIVE".equals(target)) {
                    if (!"ACTIVE".equals(application.status())) {
                        throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                                "应用未启用，不能启用密钥");
                    }
                    if (current.expired(now)) {
                        throw new LightAiException(ErrorCode.CONFIG_FIELD_IMMUTABLE,
                                "已过期密钥不能重新启用");
                    }
                }
                if (target.equals(current.status())) {
                    updated[0] = current;
                    return;
                }
                updated[0] = keys.updateStatus(connection, keyId, target, now, command.version());
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context),
                        "APPLICATION_KEY_STATUS_CHANGE", "APPLICATION_KEY", keyId.toString(),
                        List.of(
                                FieldChange.changed("status", current.status(), target),
                                FieldChange.changed("reason", null, command.reason().trim())),
                        sourceMode, context.sourceIpMasked()));
            });
            ApplicationKeyView entity = view(updated[0], now);
            return new ManagementOperationResult<>(keyId.toString(), entity.version(), entity,
                    false, null, context.requestId());
        } catch (JdbcApplicationKeyRepository.OptimisticLockException e) {
            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "应用密钥版本已变化，请刷新后重试");
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用密钥状态更新失败");
        }
    }

    public ManagementOperationResult<ApplicationKeyView> revoke(
            RequestContext context, UUID applicationId, UUID keyId,
            ApplicationKeyRevokeCommand command) {
        RequestPermissions.require(context, Permissions.APPLICATION_KEY_MANAGE);
        requireReason(command == null ? null : command.reason());
        if (command.version() < 1) throw invalid("version", "version 必须是正整数");
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            final ApplicationKeyRecord[] updated = new ApplicationKeyRecord[1];
            transaction.executeWithoutResult(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                ApplicationRecord application = loadApplication(connection, applicationId);
                requireScope(connection, context, application.code());
                loadKey(connection, applicationId, keyId);
                updated[0] = keys.revoke(connection, keyId, now, command.version());
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), context.requestId(), operatorId(context), "REVOKE",
                        "APPLICATION_KEY", keyId.toString(), List.of(
                                FieldChange.changed("status", "ACTIVE", "REVOKED"),
                                FieldChange.changed("reason", null, command.reason().trim())),
                        sourceMode, context.sourceIpMasked()));
            });
            ApplicationKeyView entity = view(updated[0], now);
            return new ManagementOperationResult<>(keyId.toString(), entity.version(), entity,
                    false, null, context.requestId());
        } catch (JdbcApplicationKeyRepository.OptimisticLockException e) {
            throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                    "应用密钥版本已变化，请刷新后重试");
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用密钥撤销失败");
        }
    }

    private ApplicationRecord loadApplication(Connection connection, UUID id) {
        return applications.findById(connection, id)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "应用不存在"));
    }

    private ApplicationKeyRecord loadKey(Connection connection, UUID applicationId, UUID keyId) {
        ApplicationKeyRecord key = keys.find(connection, keyId)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "应用密钥不存在"));
        if (!key.applicationId().equals(applicationId)) {
            throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "应用密钥不存在");
        }
        return key;
    }

    private void validateStricterLimits(Connection connection, UUID applicationId, Integer rpm, Long tpm) {
        ApplicationQuotaRecord quota = applications.findQuota(connection, applicationId).orElse(null);
        if (quota == null) return;
        if (rpm != null && quota.rpm() != null && rpm > quota.rpm()) {
            throw invalid("rpm", "密钥 RPM 不能高于应用 RPM");
        }
        if (tpm != null && quota.tpm() != null && tpm > quota.tpm()) {
            throw invalid("tpm", "密钥 TPM 不能高于应用 TPM");
        }
    }

    private ValidatedCreate validate(ApplicationKeyCreateCommand command) {
        if (command == null) throw invalid("body", "请求体必填");
        String name = command.name() == null ? "" : command.name().trim();
        if (name.length() < 2 || name.length() > 64) throw invalid("name", "名称长度为 2—64 字符");
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            throw invalid("expires_at", "有效期必须晚于当前时间");
        }
        if (command.rpm() != null && command.rpm() <= 0) throw invalid("rpm", "RPM 必须大于 0");
        if (command.tpm() != null && command.tpm() <= 0) throw invalid("tpm", "TPM 必须大于 0");
        LinkedHashSet<String> allowlist = new LinkedHashSet<>();
        for (String raw : command.ipAllowlist()) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty() || !validNetwork(value)) {
                throw invalid("ip_allowlist", "IP 白名单包含非法地址或 CIDR");
            }
            allowlist.add(value);
        }
        return new ValidatedCreate(name, List.copyOf(allowlist), command.expiresAt(),
                command.rpm(), command.tpm());
    }

    private static boolean validNetwork(String value) {
        try {
            int slash = value.indexOf('/');
            InetAddress address = InetAddress.getByName(slash < 0 ? value : value.substring(0, slash));
            if (slash < 0) return true;
            int prefix = Integer.parseInt(value.substring(slash + 1));
            return prefix >= 0 && prefix <= address.getAddress().length * 8;
        } catch (Exception e) {
            return false;
        }
    }

    private void requireScope(Connection connection, RequestContext context, String code) {
        List<String> roles = context.authContext().roles();
        if (roles.contains(Roles.SYSTEM_ADMIN) || roles.contains(Roles.OPERATOR)
                || context.authContext().applicationScope().contains("*")) return;
        LinkedHashSet<String> allowed = new LinkedHashSet<>(context.authContext().applicationScope());
        allowed.addAll(applications.findCodesForSubject(connection, context.authContext().userId()));
        if (!allowed.contains(code)) throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权访问该应用");
    }

    private static ApplicationKeyView view(ApplicationKeyRecord key, OffsetDateTime now) {
        return new ApplicationKeyView(
                key.id().toString(), key.applicationId().toString(), key.name(), key.maskedValue(),
                key.ipAllowlist(), key.expiresAt(), key.rpm(), key.tpm(), key.status().equals("ACTIVE")
                        ? key.effectiveStatus(now) : key.status(), key.lastUsedAt(), key.lastUsedIpMasked(),
                key.createdAt(), key.rotatedAt(), key.revokedAt(), key.rotationGeneration(), key.version());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw invalid("reason", "原因长度为 1—500 字符");
        }
    }

    private static String validateStatus(ApplicationKeyStatusCommand command) {
        if (command == null) throw invalid("body", "请求体必填");
        String target = command.status() == null ? "" : command.status().trim();
        if (!Set.of("ACTIVE", "DISABLED").contains(target)) {
            throw invalid("status", "密钥状态只能是 ACTIVE 或 DISABLED");
        }
        if (command.version() < 1) throw invalid("version", "version 必须是正整数");
        requireReason(command.reason());
        return target;
    }

    private static String operatorId(RequestContext context) {
        return context.authContext().userId() == null ? "system" : context.authContext().userId();
    }

    private static LightAiException invalid(String field, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, message, field);
    }

    private record ValidatedCreate(
            String name, List<String> ipAllowlist, OffsetDateTime expiresAt, Integer rpm, Long tpm) {
    }
}
