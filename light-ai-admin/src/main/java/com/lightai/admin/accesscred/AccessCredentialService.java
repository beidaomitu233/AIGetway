package com.lightai.admin.accesscred;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.security.AccessTokenService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.security.AccessCredentialCreateCommand;
import com.lightai.client.security.AccessCredentialDetail;
import com.lightai.client.security.AccessCredentialListItem;
import com.lightai.client.security.AccessCredentialRotateCommand;
import com.lightai.client.security.AccessCredentialSecretResult;
import com.lightai.client.security.AccessCredentialUpdateCommand;
import com.lightai.client.changes.FieldChange;
import com.lightai.storage.access.AccessCredentialRecord;
import com.lightai.storage.access.AccessCredentialRepository;
import com.lightai.storage.audit.AuditRecord;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Access Credential 服务（BE-044）：即时实体全生命周期。
 * 启停/轮换/删除即时生效，不走草稿发布；轮换 generation+1 且旧 Token 立即拒绝；
 * token_value 只在签发/轮换响应出现一次；已过期凭证不能重新启用；
 * application 由凭证决定，客户端不能伪造。
 */
public class AccessCredentialService {

    public static final Set<String> SORTABLE = Set.of(
            "name", "application", "enabled", "rotation_generation", "expires_at", "created_at", "updated_at");

    private final DataSource dataSource;
    private final AccessCredentialRepository repository;
    private final AccessTokenService tokenService;
    private final Supplier<AuditService> auditService;
    private final Clock clock;
    private final String sourceMode;
    private final boolean standaloneMode;

    public AccessCredentialService(DataSource dataSource, AccessCredentialRepository repository,
                                   AccessTokenService tokenService, Supplier<AuditService> auditService,
                                   Clock clock, String sourceMode, boolean standaloneMode) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.clock = clock;
        this.sourceMode = sourceMode;
        this.standaloneMode = standaloneMode;
    }

    public PageResult<AccessCredentialListItem> list(RequestContext context, java.util.Map<String, String> params) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.ACCESS_VIEW);
        requireStandalone();
        String keyword = params.get("keyword");
        String application = params.get("application");
        String status = params.get("status");
        String allowedAliasId = params.get("allowed_alias_id");
        Boolean hasRecentUse = params.get("has_recent_use") == null ? null
                : Boolean.parseBoolean(params.get("has_recent_use"));
        com.lightai.admin.query.ListQuerySupport.ListQuery query =
                com.lightai.admin.query.ListQuerySupport.parse(params.get("page"), params.get("page_size"),
                        params.get("sort"), SORTABLE, "created_at desc");
        try (Connection connection = dataSource.getConnection()) {
            StringBuilder filterSql = new StringBuilder();
            List<Object> filters = new ArrayList<>();
            if (application != null && !application.isBlank()) {
                filterSql.append("application = ?");
                filters.add(application.trim());
            }
            if (keyword != null && !keyword.isBlank()) {
                filterSql.append(filterSql.length() > 0 ? " AND " : "").append("name ILIKE ?");
                filters.add("%" + keyword.trim() + "%");
            }
            String filter = filterSql.toString().trim();
            List<AccessCredentialRecord> records = repository.list(
                    connection, filter, filters, query.sort(), query.offset(), query.limit());
            long total = repository.count(connection, filter, filters);

            OffsetDateTime now = OffsetDateTime.now(clock);
            List<AccessCredentialListItem> items = records.stream()
                    .filter(record -> status == null || status.isBlank()
                            || status.equals(record.status(now)))
                    .filter(record -> allowedAliasId == null || matchesAlias(connection, record.id(),
                            UUID.fromString(allowedAliasId)))
                    .filter(record -> hasRecentUse == null || hasRecentUse == (record.lastUsedAt() != null))
                    .map(record -> toListItem(record, aliasIds(connection, record.id()), now))
                    .toList();
            return new PageResult<>(items, total, query.page(), query.pageSize(), query.sort(), now, now);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.AUDIT_DATA_UNAVAILABLE, "访问凭证读取失败");
        }
    }

    public AccessCredentialDetail get(RequestContext context, UUID id) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.ACCESS_VIEW);
        requireStandalone();
        try (Connection connection = dataSource.getConnection()) {
            AccessCredentialRecord record = load(connection, id);
            return toDetail(record, repository.aliasIdsOf(connection, id));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "访问凭证读取失败");
        }
    }

    /** 签发：token_value 只本次返回；持久化摘要。 */
    public AccessCredentialSecretResult create(RequestContext context, AccessCredentialCreateCommand command) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.ACCESS_MANAGE);
        requireStandalone();
        String name = requireName(command.name());
        String application = requireApplication(command.application());
        List<UUID> aliases = normalizedAliases(command.allowedAliasIds());
        AccessTokenService.Issued issued = tokenService.issue();
        OffsetDateTime now = OffsetDateTime.now(clock);
        AccessCredentialRecord record = new AccessCredentialRecord(
                UUID.randomUUID(), name, application, issued.prefix(), issued.tokenHash(),
                issued.pepperVersion(), issued.maskedValue(),
                command.ipAllowlist() == null ? List.of() : List.copyOf(command.ipAllowlist()),
                command.expiresAt(), command.enabled() == null || command.enabled(),
                1L, now, null, null, null, 1L, now, now, null);
        try (Connection connection = dataSource.getConnection()) {
            if (repository.existsAliveByName(connection, name)) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "名称已存在", "name");
            }
            repository.insert(connection, record, aliases);
            auditService.get().recordSuccess(connection, AuditRecord.succeeded(
                    UUID.randomUUID(), context.requestId(), operatorId(context), "CREATE",
                    "ACCESS_CREDENTIAL", record.id().toString(), List.of(
                    FieldChange.changed("name", null, name),
                    FieldChange.sensitiveChanged("token_value")),
                    sourceMode, context.sourceIpMasked()));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "访问凭证写入失败");
        }
        return new AccessCredentialSecretResult(record.id().toString(), issued.tokenValue(),
                issued.maskedValue(), now, 1L, record.version());
    }

    public ManagementOperationResult<AccessCredentialDetail> update(UUID id, AccessCredentialUpdateCommand command,
                                                                    RequestContext context) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.ACCESS_MANAGE);
        requireStandalone();
        String name = requireName(command.name());
        String application = requireApplication(command.application());
        try (Connection connection = dataSource.getConnection()) {
            AccessCredentialRecord current = load(connection, id);
            if (current.version() != command.version()) {
                throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象版本已变化，请刷新后重试",
                        null, context.requestId(), null, current.version(), null, null);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            AccessCredentialRecord updated = new AccessCredentialRecord(
                    current.id(), name, application, current.tokenPrefix(), current.tokenHash(),
                    current.tokenHashVersion(), current.maskedValue(),
                    command.ipAllowlist() == null ? List.of() : List.copyOf(command.ipAllowlist()),
                    command.expiresAt(), current.enabled(), current.rotationGeneration(),
                    current.issuedAt(), current.rotatedAt(), current.lastUsedAt(), current.lastUsedIpMasked(),
                    command.version() + 1, current.createdAt(), now, null);
            repository.update(connection, updated, normalizedAliases(command.allowedAliasIds()));
            auditService.get().recordSuccess(connection, AuditRecord.succeeded(
                    UUID.randomUUID(), context.requestId(), operatorId(context), "UPDATE",
                    "ACCESS_CREDENTIAL", id.toString(), List.of(
                    FieldChange.changed("name", current.name(), name)), sourceMode, context.sourceIpMasked()));
            return new ManagementOperationResult<>(id.toString(), updated.version(), get(context, id), false, null,
                    context.requestId());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "访问凭证更新失败");
        }
    }

    /** 轮换：generation+1，旧 Token 摘要被覆盖立即失效，enabled 不变。 */
    public AccessCredentialSecretResult rotate(UUID id, AccessCredentialRotateCommand command, RequestContext context) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.ACCESS_MANAGE);
        requireStandalone();
        AccessTokenService.Issued issued = tokenService.issue();
        try (Connection connection = dataSource.getConnection()) {
            AccessCredentialRecord current = load(connection, id);
            if (current.version() != command.version()) {
                throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象版本已变化，请刷新后重试",
                        null, context.requestId(), null, current.version(), null, null);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            AccessCredentialRecord rotated = new AccessCredentialRecord(
                    current.id(), current.name(), current.application(), issued.prefix(), issued.tokenHash(),
                    issued.pepperVersion(), issued.maskedValue(), current.ipAllowlist(), current.expiresAt(),
                    current.enabled(), current.rotationGeneration() + 1, now, now,
                    current.lastUsedAt(), current.lastUsedIpMasked(), current.version() + 1,
                    current.createdAt(), now, null);
            repository.update(connection, rotated, repository.aliasIdsOf(connection, id));
            auditService.get().recordSuccess(connection, AuditRecord.succeeded(
                    UUID.randomUUID(), context.requestId(), operatorId(context), "ROTATE",
                    "ACCESS_CREDENTIAL", id.toString(), List.of(FieldChange.sensitiveChanged("token_value")),
                    sourceMode, context.sourceIpMasked()));
            return new AccessCredentialSecretResult(id.toString(), issued.tokenValue(), issued.maskedValue(),
                    now, rotated.rotationGeneration(), rotated.version());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "访问凭证轮换失败");
        }
    }

    public ManagementOperationResult<AccessCredentialDetail> changeEnabled(UUID id, boolean enable, long version,
                                                                           RequestContext context) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.ACCESS_MANAGE);
        requireStandalone();
        try (Connection connection = dataSource.getConnection()) {
            AccessCredentialRecord current = load(connection, id);
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (enable && current.expired(now)) {
                throw new LightAiException(ErrorCode.ACCESS_CREDENTIAL_EXPIRED, "已过期的访问凭证不能重新启用");
            }
            if (current.version() != version) {
                throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象版本已变化，请刷新后重试",
                        null, context.requestId(), null, current.version(), null, null);
            }
            AccessCredentialRecord updated = new AccessCredentialRecord(
                    current.id(), current.name(), current.application(), current.tokenPrefix(),
                    current.tokenHash(), current.tokenHashVersion(), current.maskedValue(),
                    current.ipAllowlist(), current.expiresAt(), enable, current.rotationGeneration(),
                    current.issuedAt(), current.rotatedAt(), current.lastUsedAt(), current.lastUsedIpMasked(),
                    version + 1, current.createdAt(), now, null);
            repository.update(connection, updated, repository.aliasIdsOf(connection, id));
            auditService.get().recordSuccess(connection, AuditRecord.succeeded(
                    UUID.randomUUID(), context.requestId(), operatorId(context), enable ? "ENABLE" : "DISABLE",
                    "ACCESS_CREDENTIAL", id.toString(), List.of(
                    FieldChange.changed("enabled", current.enabled(), enable)),
                    sourceMode, context.sourceIpMasked()));
            return new ManagementOperationResult<>(id.toString(), updated.version(), get(context, id), false, null,
                    context.requestId());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "访问凭证启停失败");
        }
    }

    /** 删除：DELETED 不可恢复，摘要立即失效。 */
    public ManagementOperationResult<AccessCredentialDetail> delete(UUID id, long version, String reason,
                                                                    RequestContext context) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.ACCESS_MANAGE);
        requireStandalone();
        try (Connection connection = dataSource.getConnection()) {
            AccessCredentialRecord current = load(connection, id);
            if (current.version() != version) {
                throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象版本已变化，请刷新后重试",
                        null, context.requestId(), null, current.version(), null, null);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            AccessCredentialRecord deleted = new AccessCredentialRecord(
                    current.id(), current.name(), current.application(), current.tokenPrefix(),
                    current.tokenHash(), current.tokenHashVersion(), current.maskedValue(),
                    current.ipAllowlist(), current.expiresAt(), current.enabled(), current.rotationGeneration(),
                    current.issuedAt(), current.rotatedAt(), current.lastUsedAt(), current.lastUsedIpMasked(),
                    version + 1, current.createdAt(), now, now);
            repository.update(connection, deleted, List.of());
            auditService.get().recordSuccess(connection, AuditRecord.succeeded(
                    UUID.randomUUID(), context.requestId(), operatorId(context), "DELETE",
                    "ACCESS_CREDENTIAL", id.toString(), List.of(), sourceMode, context.sourceIpMasked()));
            return new ManagementOperationResult<>(id.toString(), deleted.version(), null, false, null,
                    context.requestId());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "访问凭证删除失败");
        }
    }

    private static String operatorId(RequestContext context) {
        return context.authContext().userId() == null ? "system" : context.authContext().userId();
    }

    private void requireStandalone() {
        if (!standaloneMode) {
            throw new LightAiException(ErrorCode.MODE_NOT_SUPPORTED, "当前运行模式不支持 Access Credential");
        }
    }

    private boolean matchesAlias(Connection connection, UUID credentialId, UUID aliasId) {
        return repository.aliasIdsOf(connection, credentialId).contains(aliasId);
    }

    private List<UUID> aliasIds(Connection connection, UUID credentialId) {
        return repository.aliasIdsOf(connection, credentialId);
    }

    private AccessCredentialRecord load(Connection connection, UUID id) {
        return repository.find(connection, id)
                .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "访问凭证不存在或已删除"));
    }

    private static AccessCredentialListItem toListItem(AccessCredentialRecord record, List<UUID> aliases,
                                                       OffsetDateTime now) {
        return new AccessCredentialListItem(
                record.id().toString(), record.name(), record.application(), record.status(now),
                record.maskedValue(), aliases.stream().map(UUID::toString).toList(),
                record.ipAllowlist() == null ? 0 : record.ipAllowlist().size(),
                record.expiresAt(), record.lastUsedAt(), record.rotationGeneration(), false,
                record.createdAt(), record.updatedAt(), record.version());
    }

    private static AccessCredentialDetail toDetail(AccessCredentialRecord record, List<UUID> aliases) {
        return new AccessCredentialDetail(
                record.id().toString(), record.name(), record.application(), record.status(OffsetDateTime.now()),
                record.maskedValue(), aliases.stream().map(UUID::toString).toList(),
                record.ipAllowlist() == null ? List.of() : record.ipAllowlist(),
                record.expiresAt(), record.enabled(), record.rotationGeneration(), record.issuedAt(),
                record.rotatedAt(), record.lastUsedAt(), record.lastUsedIpMasked(),
                record.createdAt(), record.updatedAt(), record.version());
    }

    private static String requireName(String name) {
        if (name == null || name.trim().length() < 2 || name.trim().length() > 64) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "name 长度 2—64", "name");
        }
        return name.trim();
    }

    private static String requireApplication(String application) {
        if (application == null || application.isBlank() || application.trim().length() > 64) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "application 1—64", "application");
        }
        return application.trim();
    }

    private static List<UUID> normalizedAliases(List<String> aliases) {
        if (aliases == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String alias : aliases) {
            try {
                ids.add(UUID.fromString(alias));
            } catch (IllegalArgumentException e) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                        "allowed_alias_ids 含非法 ID", "allowed_alias_ids");
            }
        }
        return ids;
    }

    /** 供 Map 视图使用的别名（保留 Optional 语义扩展点）。 */
    @SuppressWarnings("unused")
    private static Optional<UUID> optionalId(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
