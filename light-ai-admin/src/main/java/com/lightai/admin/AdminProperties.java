package com.lightai.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理端装配属性。runtime_mode 取值见 RuntimeMode（C-003）；
 * ui/admin 基础路径与 bootstrap 输出一致（C-023）。
 */
@ConfigurationProperties(prefix = "light-ai.admin")
public class AdminProperties {

    private String runtimeMode = "EMBEDDED";
    private String uiBasePath = "";
    private String adminApiBasePath = "";
    private String timezone = "Asia/Shanghai";
    private boolean csrfEnabled = false;
    private boolean allowedProviderInternalNetworks = false;

    public boolean isAllowedProviderInternalNetworks() {
        return allowedProviderInternalNetworks;
    }

    public void setAllowedProviderInternalNetworks(boolean allowedProviderInternalNetworks) {
        this.allowedProviderInternalNetworks = allowedProviderInternalNetworks;
    }

    public String getRuntimeMode() {
        return runtimeMode;
    }

    public void setRuntimeMode(String runtimeMode) {
        this.runtimeMode = runtimeMode;
    }

    public String getUiBasePath() {
        return uiBasePath;
    }

    public void setUiBasePath(String uiBasePath) {
        this.uiBasePath = uiBasePath;
    }

    public String getAdminApiBasePath() {
        return adminApiBasePath;
    }

    public void setAdminApiBasePath(String adminApiBasePath) {
        this.adminApiBasePath = adminApiBasePath;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public boolean isCsrfEnabled() {
        return csrfEnabled;
    }

    public void setCsrfEnabled(boolean csrfEnabled) {
        this.csrfEnabled = csrfEnabled;
    }
}
