package com.lightai.runtime.ports;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.List;
import java.util.Optional;

/**
 * 业务访问凭证端口（BE-044 交付前为桩）：Bearer Token 校验与授权范围。
 * 鉴权失败不产生 Provider 调用。
 */
public interface AccessTokenPort {

    /** 校验 Bearer Token；无效/过期/停用抛 ACCESS_TOKEN_INVALID。 */
    Principal authenticate(String bearerToken);

    /** application 由凭证决定，客户端不能伪造；空 allowed_alias_ids 表示全部已发布 Alias。 */
    record Principal(String application, List<String> allowedAliasIds) {

        public Principal {
            application = application == null ? "default" : application;
            allowedAliasIds = allowedAliasIds == null ? List.of() : List.copyOf(allowedAliasIds);
        }

        public boolean aliasAllowed(String alias) {
            return allowedAliasIds.isEmpty() || allowedAliasIds.contains(alias);
        }
    }

    static AccessTokenPort denyAll() {
        return token -> {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "业务访问凭证无效");
        };
    }

    static AccessTokenPort singleToken(String expectedToken, String application) {
        return token -> {
            if (token == null || !constantEquals(token, expectedToken)) {
                throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "业务访问凭证无效");
            }
            return new Principal(application, List.of());
        };
    }

    private static boolean constantEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 运行参数端口（default_alias_id，C-010）。 */
    interface RuntimeConfigPort {
        Optional<String> defaultAliasId();
    }
}
