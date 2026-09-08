package com.lightai.spi.auth;

import java.util.List;
import java.util.Set;

/**
 * 一次请求建立一次、不可变的管理身份上下文（BACKEND_PLAN 2.3）。
 * 应用数据范围与可调用 Alias 范围分开表达，避免权限域混用。
 */
public record AuthContext(
        boolean authenticated,
        String userId,
        String displayName,
        List<String> roles,
        List<String> applicationScope,
        List<String> aliasScope) {

    public AuthContext {
        roles = roles == null ? List.of() : List.copyOf(roles);
        applicationScope = applicationScope == null ? List.of() : List.copyOf(applicationScope);
        aliasScope = aliasScope == null ? List.of() : List.copyOf(aliasScope);
    }

    /** 保持既有宿主身份适配器源码兼容；未声明 Alias 范围时不授予 Alias。 */
    public AuthContext(boolean authenticated, String userId, String displayName,
                       List<String> roles, List<String> applicationScope) {
        this(authenticated, userId, displayName, roles, applicationScope, List.of());
    }

    public static AuthContext anonymous() {
        return new AuthContext(false, null, null, List.of(), List.of(), List.of());
    }

    public static AuthContext authenticated(String userId, String displayName,
                                            Set<String> roles, List<String> applicationScope) {
        return authenticated(userId, displayName, roles, applicationScope, List.of());
    }

    public static AuthContext authenticated(String userId, String displayName,
                                            Set<String> roles, List<String> applicationScope,
                                            List<String> aliasScope) {
        return new AuthContext(true, userId, displayName,
                roles == null ? List.of() : List.copyOf(roles),
                applicationScope == null ? List.of() : List.copyOf(applicationScope),
                aliasScope == null ? List.of() : List.copyOf(aliasScope));
    }
}