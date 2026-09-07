package com.lightai.server.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Standalone 部署身份配置（C-001 扩展点）：
 * adminToken 与 trustedLocal 均默认关闭；两者都未启用时管理请求默认拒绝。
 */
@ConfigurationProperties(prefix = "light-ai.server.auth")
public class ServerAuthProperties {

    /** 部署管理令牌；配置后 X-Admin-Token 匹配的请求获得 SYSTEM_ADMIN 身份。 */
    private String adminToken;

    /** 本机信任：回环地址请求获得 SYSTEM_ADMIN 身份（仅本机管理场景）。 */
    private boolean trustedLocal = false;

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public boolean isTrustedLocal() {
        return trustedLocal;
    }

    public void setTrustedLocal(boolean trustedLocal) {
        this.trustedLocal = trustedLocal;
    }
}
