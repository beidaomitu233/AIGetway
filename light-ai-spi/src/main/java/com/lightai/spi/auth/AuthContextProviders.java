package com.lightai.spi.auth;

/**
 * 身份提供缺省实现。
 * Embedded Admin 缺少宿主认证适配时使用 denyAll：所有管理请求 403 ACCESS_DENIED，
 * 不新增匿名入口。
 */
public final class AuthContextProviders {

    private static final AuthContextProvider DENY_ALL = request -> AuthContext.anonymous();

    private AuthContextProviders() {
    }

    public static AuthContextProvider denyAll() {
        return DENY_ALL;
    }
}
