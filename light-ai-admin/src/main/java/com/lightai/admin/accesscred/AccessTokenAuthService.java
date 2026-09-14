package com.lightai.admin.accesscred;

import com.lightai.client.application.ApplicationModelConstraint;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.storage.application.ApplicationKeyRecord;
import com.lightai.storage.application.ApplicationModelPermissionRecord;
import com.lightai.storage.application.ApplicationQuotaRecord;
import com.lightai.storage.application.ApplicationRecord;
import com.lightai.storage.application.JdbcApplicationKeyRepository;
import com.lightai.storage.application.JdbcApplicationModelMappingRepository;
import com.lightai.storage.application.JdbcApplicationRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** V2 /v1 鉴权：只接受平台签发的应用密钥。 */
public class AccessTokenAuthService implements AccessTokenPort {

    private final DataSource dataSource;
    private final com.lightai.admin.security.AccessTokenService tokenService;
    private final Clock clock;
    private final JdbcApplicationRepository applications;
    private final JdbcApplicationKeyRepository applicationKeys;
    private final JdbcApplicationModelMappingRepository modelMappings;

    public AccessTokenAuthService(DataSource dataSource,
                                  com.lightai.admin.security.AccessTokenService tokenService,
                                  Clock clock,
                                  JdbcApplicationRepository applications,
                                  JdbcApplicationKeyRepository applicationKeys,
                                  JdbcApplicationModelMappingRepository modelMappings) {
        this.dataSource = dataSource;
        this.tokenService = tokenService;
        this.clock = clock;
        this.applications = applications;
        this.applicationKeys = applicationKeys;
        this.modelMappings = modelMappings;
    }

    @Override
    public Principal authenticate(String bearerToken) {
        return authenticate(bearerToken, null);
    }

    @Override
    public Principal authenticate(String bearerToken, String sourceIp) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用密钥无效");
        }
        try (Connection connection = dataSource.getConnection()) {
            byte[] hash = tokenService.digest(bearerToken.trim());
            Optional<ApplicationKeyRecord> found = applicationKeys.findByDigest(connection, hash);
            if (found.isEmpty()) {
                throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用密钥无效");
            }
            ApplicationKeyRecord key = found.get();
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (!java.security.MessageDigest.isEqual(
                    key.keyDigest(), tokenService.digest(bearerToken.trim(), key.digestVersion()))) {
                throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用密钥无效");
            }
            if (!"ACTIVE".equals(key.status()) || key.revokedAt() != null) {
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
            List<String> permissionAliases = permissions.stream()
                    .map(ApplicationModelPermissionRecord::virtualModelCode).toList();
            Map<String, String> publicMappings = modelMappings.runtimeMappings(connection, application.id());
            List<String> aliases = new ArrayList<>(permissionAliases);
            for (String publicName : publicMappings.keySet()) {
                if (!aliases.contains(publicName)) aliases.add(publicName);
            }
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
            return AccessTokenPort.Principal.enterprise(
                    application.code(), aliases, application.id().toString(), key.id().toString(),
                    rpm, tpm, constraints, publicMappings);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用密钥无效");
        }
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

    private static boolean isAllowedSource(List<String> allowlist, String sourceIp) {
        if (allowlist == null || allowlist.isEmpty()) return true;
        if (sourceIp == null || sourceIp.isBlank()) return false;
        try {
            byte[] actual = InetAddress.getByName(sourceIp.strip()).getAddress();
            for (String raw : allowlist) {
                if (raw == null || raw.isBlank()) continue;
                String value = raw.strip();
                int slash = value.indexOf('/');
                byte[] expected = InetAddress.getByName(
                        slash < 0 ? value : value.substring(0, slash)).getAddress();
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
            // 无效来源地址按拒绝处理。
        }
        return false;
    }
}
