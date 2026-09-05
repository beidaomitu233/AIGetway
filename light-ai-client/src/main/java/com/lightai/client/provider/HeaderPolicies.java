package com.lightai.client.provider;

import java.util.Locale;
import java.util.Set;

/**
 * Provider default_headers 键约束：认证与凭据类请求头一律拒绝，
 * 密钥走 Credential 体系，不允许在 Provider 连接配置中携带。
 */
public final class HeaderPolicies {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "authorization", "proxy-authorization", "x-api-key", "api-key", "apikey",
            "x-auth-token", "x-goog-api-key", "cookie", "set-cookie");

    private HeaderPolicies() {
    }

    public static boolean isAuthHeader(String key) {
        if (key == null) {
            return true;
        }
        return FORBIDDEN_KEYS.contains(key.toLowerCase(Locale.ROOT).strip());
    }
}
