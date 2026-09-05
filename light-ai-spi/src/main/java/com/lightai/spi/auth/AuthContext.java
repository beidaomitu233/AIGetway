package com.lightai.spi.auth;

import java.util.List;
import java.util.Set;

/**
 * 一次请求建立一次、不可变的管理身份上下文（BACKEND_PLAN 2.3）。
 * 未认证上下文用于默认拒绝路径，不得携带任何角色或权限。
 */
public record AuthContext(
        boolean authenticated,
        String userId,
        String displayName,
        List<String> roles,
        List<String> applicationScope) {

    public AuthContext {
        roles = roles == null ? List.of() : List.copyOf(roles);
        applicationScope = applicationScope == null ? List.of() : List.copyOf(applicationScope);
    }

    public static AuthContext anonymous() {
        return new AuthContext(false, null, null, List.of(), List.of());
    }

    public static AuthContext authenticated(String userId, String displayName,
                                            Set<String> roles, List<String> applicationScope) {
        return new AuthContext(true, userId, displayName,
                roles == null ? List.of() : List.copyOf(roles),
                applicationScope == null ? List.of() : List.copyOf(applicationScope));
    }
}
