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

    private void recordClientIp(Connection connection, AccessCredentialRecord record, OffsetDateTime now) {
        if (!recordClientIp) {
            return;
        }
        repository.touch(connection, record.id(), now, "recorded");
    }

}
