package com.lightai.admin.accesscred;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.storage.access.AccessCredentialRecord;
import com.lightai.storage.access.AccessCredentialRepository;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private final com.lightai.admin.security.AccessTokenService tokenService;
    private final java.time.Clock clock;
    private final boolean recordClientIp;

    public AccessTokenAuthService(DataSource dataSource, AccessCredentialRepository repository,
                                  com.lightai.admin.security.AccessTokenService tokenService,
                                  java.time.Clock clock, boolean recordClientIp) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.tokenService = tokenService;
        this.clock = clock;
        this.recordClientIp = recordClientIp;
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
            List<String> allowedAliasIds = repository.aliasIdsOf(connection, record.id()).stream()
                    .map(java.util.Objects::toString).toList();
            recordClientIp(connection, record, now);
            return new AccessTokenPort.Principal(record.application(), allowedAliasIds);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "业务访问凭证无效");
        }
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
