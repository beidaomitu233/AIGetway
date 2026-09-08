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

    default Principal authenticate(String bearerToken, String sourceIp) {
        return authenticate(bearerToken);
    }

    /** application 由凭证决定，客户端不能伪造；空 allowed_alias_ids 表示全部已发布 Alias。 */
    record Principal(
            String application,
            List<String> allowedAliasIds,
            String applicationId,
            String applicationKeyId,
            Integer rpm,
            Long tpm,
            boolean allAliasesAllowed) {

        public Principal {
            application = application == null ? "default" : application;
            allowedAliasIds = allowedAliasIds == null ? List.of() : List.copyOf(allowedAliasIds);
        }

        /** 旧运行入口保持“空白名单表示全部 Alias”的兼容语义。 */
        public Principal(String application, List<String> allowedAliasIds) {
            this(application, allowedAliasIds, null, null, null, null, true);
        }

        /** V2 企业应用入口：即使未授权任何模型，也必须解释为拒绝全部模型。 */
        public static Principal enterprise(
                String application, List<String> allowedAliasIds, String applicationId,
                String applicationKeyId, Integer rpm, Long tpm) {
            return new Principal(application, allowedAliasIds, applicationId,
                    applicationKeyId, rpm, tpm, false);
        }

        public boolean aliasAllowed(String alias) {
            return allAliasesAllowed || allowedAliasIds.contains(alias);
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
