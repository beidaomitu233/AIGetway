package com.lightai.admin.accesscred;

import com.lightai.client.application.ApplicationModelConstraint;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.storage.access.AccessCredentialRecord;
import com.lightai.storage.access.AccessCredentialRepository;
import com.lightai.storage.application.ApplicationKeyRecord;
import com.lightai.storage.application.ApplicationModelPermissionRecord;
import com.lightai.storage.application.ApplicationQuotaRecord;
import com.lightai.storage.application.ApplicationRecord;
import com.lightai.storage.application.JdbcApplicationKeyRepository;
import com.lightai.storage.application.JdbcApplicationRepository;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.sql.DataSource;

/**
 * /v1 业务鉴权（BE-044）：实现 runtime AccessTokenPort。
 * 摘要匹配 → 活行 → enabled/expires → Alias 白名单 → IP 允许（可选）→
 * Principal(application 由凭证决定)；鉴权失败不产生 Provider 调用；
 * 成功路径记录活动摘要。
 */
public class AccessTokenAuthService implements AccessTokenPort {

    private final DataSource dataSource;
    private final AccessCredentialRepository repository;
    private final com.lightai.storage.alias.JdbcAliasRepository aliasRepository;
    private final com.lightai.admin.security.AccessTokenService tokenService;
    private final java.time.Clock clock;
    private final boolean recordClientIp;
    private final JdbcApplicationRepository applications;
    private final JdbcApplicationKeyRepository applicationKeys;

    public AccessTokenAuthService(DataSource dataSource, AccessCredentialRepository repository,
                                  com.lightai.storage.alias.JdbcAliasRepository aliasRepository,
                                  com.lightai.admin.security.AccessTokenService tokenService,
                                  java.time.Clock clock, boolean recordClientIp) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.aliasRepository = aliasRepository;
        this.tokenService = tokenService;
        this.clock = clock;
        this.recordClientIp = recordClientIp;
        this.applications = null;
        this.applicationKeys = null;
    }

    public AccessTokenAuthService(DataSource dataSource, AccessCredentialRepository repository,
                                  com.lightai.storage.alias.JdbcAliasRepository aliasRepository,
                                  com.lightai.admin.security.AccessTokenService tokenService,
                                  java.time.Clock clock, boolean recordClientIp,
                                  JdbcApplicationRepository applications,
                                  JdbcApplicationKeyRepository applicationKeys) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.aliasRepository = aliasRepository;
        this.tokenService = tokenService;
        this.clock = clock;
        this.recordClientIp = recordClientIp;
        this.applications = applications;
        this.applicationKeys = applicationKeys;
    }

    @Override
    public Principal authenticate(String bearerToken) {
        return authenticate(bearerToken, null);
    }

    @Override
    public Principal authenticate(String bearerToken, String sourceIp) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "业务访问凭证无效");
        }
        byte[] hash = tokenService.digest(bearerToken.trim());
        try (Connection connection = dataSource.getConnection()) {
            Principal applicationPrincipal = authenticateApplicationKey(
                    connection, hash, bearerToken.trim(), sourceIp);
            if (applicationPrincipal != null) {
                return applicationPrincipal;
            }
            Optional<AccessCredentialRecord> found = repository.findByTokenHash(connection, hash);
            if (found.isEmpty()) {
                throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "业务访问凭证无效");
            }
            AccessCredentialRecord record = found.get();
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (!record.alive() || !record.enabled()) {
                throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "业务访问凭证无效或已停用");
            }
            if (record.expired(now)) {
                throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "业务访问凭证已过期");
            }
            if (!isAllowedSource(record.ipAllowlist(), sourceIp)) {
                throw new LightAiException(ErrorCode.ACCESS_IP_DENIED, "请求来源不在业务访问凭证允许的 IP 范围");
            }
            List<String> allowedAliasNames = resolveAliasNames(connection, record.id());
            recordClientIp(connection, record, now);
            return new AccessTokenPort.Principal(record.application(), allowedAliasNames);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "业务访问凭证无效");
        }
    }

    private Principal authenticateApplicationKey(
            Connection connection, byte[] hash, String bearerToken, String sourceIp) {
        if (applications == null || applicationKeys == null) return null;
        Optional<ApplicationKeyRecord> found = applicationKeys.findByDigest(connection, hash);
        if (found.isEmpty()) return null;
        ApplicationKeyRecord key = found.get();
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!java.security.MessageDigest.isEqual(
                key.keyDigest(), tokenService.digest(bearerToken, key.digestVersion()))) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用密钥无效");
        }
        if (!key.status().equals("ACTIVE") || key.revokedAt() != null) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用密钥已停用或撤销");
        }
        if (key.expiresAt() != null && !key.expiresAt().isAfter(now)) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用密钥已过期");
        }
        if (!isAllowedSource(key.ipAllowlist(), sourceIp)) {
            throw new LightAiException(ErrorCode.ACCESS_IP_DENIED, "请求来源不在应用密钥允许的 IP 范围");
        }
        ApplicationRecord application = applications.findById(connection, key.applicationId())
                .orElseThrow(() -> new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用不存在"));
        if (!"ACTIVE".equals(application.status())) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用已停用或归档");
        }
        List<UUID> keyModelIds = applicationKeys.listModelIds(connection, key.id());
        List<ApplicationModelPermissionRecord> permissions = applications
                .listModelPermissions(connection, application.id()).stream()
                .filter(permission -> permission.enabled() && permission.virtualModelCode() != null)
                .filter(permission -> keyModelIds.isEmpty()
                        || keyModelIds.contains(permission.virtualModelId()))
                .toList();
        List<String> aliases = permissions.stream()
                .map(ApplicationModelPermissionRecord::virtualModelCode).toList();
        Map<String, ApplicationModelConstraint> constraints = new LinkedHashMap<>();
        for (ApplicationModelPermissionRecord permission : permissions) {
            ApplicationModelConstraint constraint = ApplicationModelConstraint.fromJson(
                    permission.virtualModelCode(), permission.constraintsJson());
            if (!constraint.isEmpty()) {
                constraints.put(permission.virtualModelCode(), constraint);
            }
        }
        ApplicationQuotaRecord quota = applications.findQuota(connection, application.id()).orElse(null);
        Integer rpm = stricter(key.rpm(), quota == null ? null : quota.rpm());
        Long tpm = stricter(key.tpm(), quota == null ? null : quota.tpm());
        if (recordClientIp) applicationKeys.touch(connection, key.id(), now, "recorded");
        return AccessTokenPort.Principal.enterprise(
                application.code(), aliases, application.id().toString(), key.id().toString(),
                rpm, tpm, constraints);
    }

    private static Integer stricter(Integer left, Integer right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.min(left, right);
    }

    private static Long stricter(Long left, Long right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.min(left, right);
    }

    /** access_credential_alias 存储授权 Alias 的 UUID；运行端口按 alias 名称做范围判定，这里完成翻译。 */
    private List<String> resolveAliasNames(Connection connection, UUID credentialId) {
        List<java.util.UUID> aliasIds = repository.aliasIdsOf(connection, credentialId);
        if (aliasIds.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (java.util.UUID aliasId : aliasIds) {
            names.add(aliasRepository.findLiveById(connection, aliasId)
                    .map(record -> record.alias())
                    .orElse(aliasId.toString()));
        }
        return List.copyOf(names);
    }

    private static boolean isAllowedSource(List<String> allowlist, String sourceIp) {
        if (allowlist == null || allowlist.isEmpty()) return true;
        if (sourceIp == null || sourceIp.isBlank()) return false;
        try {
            byte[] actual = InetAddress.getByName(sourceIp.strip()).getAddress();
            for (String raw : allowlist) {
                if (raw == null || raw.isBlank()) continue;
                String value = raw.strip();
                int slash = value.indexOf('/');
                byte[] expected = InetAddress.getByName(slash < 0 ? value : value.substring(0, slash)).getAddress();
                if (expected.length != actual.length) continue;
                int prefix = slash < 0 ? expected.length * 8 : Integer.parseInt(value.substring(slash + 1));
                if (prefix < 0 || prefix > expected.length * 8) continue;
                int fullBytes = prefix / 8;
                int remaining = prefix % 8;
                boolean match = true;
                for (int i = 0; i < fullBytes; i++) {
                    if (actual[i] != expected[i]) { match = false; break; }
                }
                if (match && remaining > 0) {
                    int mask = 0xff << (8 - remaining);
                    match = (actual[fullBytes] & mask) == (expected[fullBytes] & mask);
                }
                if (match) return true;
            }
        } catch (UnknownHostException | NumberFormatException ignored) {
        }
        return false;
    }

    private void recordClientIp(Connection connection, AccessCredentialRecord record, OffsetDateTime now) {
        if (!recordClientIp) {
            return;
        }
        repository.touch(connection, record.id(), now, "recorded");
    }

}
