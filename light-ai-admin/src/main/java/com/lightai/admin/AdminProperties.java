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
    private String secretMasterKeyBase64;
    private String secretMasterKeyId = "primary";
    /** 内部实例认证共享口令（BE-041）；未配置时 /internal/** 一律拒绝（默认拒绝匿名）。 */
    private String internalInstanceToken;
    /** 发布实例准备时限秒数（BE-040/042 超时收敛判定）。 */
    private int publishInstanceTimeoutSeconds = 300;
    /** 实例失联阈值秒数：超过为 STALE，超过三倍为 OFFLINE（4.5.2.5）。 */
    private int instanceStaleSeconds = 45;

    public String getSecretMasterKeyBase64() {
        return secretMasterKeyBase64;
    }

    public void setSecretMasterKeyBase64(String secretMasterKeyBase64) {
        this.secretMasterKeyBase64 = secretMasterKeyBase64;
    }

    public String getSecretMasterKeyId() {
        return secretMasterKeyId;
    }

    public void setSecretMasterKeyId(String secretMasterKeyId) {
        this.secretMasterKeyId = secretMasterKeyId;
    }

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

    public String getInternalInstanceToken() {
        return internalInstanceToken;
    }

    public void setInternalInstanceToken(String internalInstanceToken) {
        this.internalInstanceToken = internalInstanceToken;
    }

    public int getPublishInstanceTimeoutSeconds() {
        return publishInstanceTimeoutSeconds;
    }

    public void setPublishInstanceTimeoutSeconds(int publishInstanceTimeoutSeconds) {
        this.publishInstanceTimeoutSeconds = publishInstanceTimeoutSeconds;
    }

    public int getInstanceStaleSeconds() {
        return instanceStaleSeconds;
    }

    public void setInstanceStaleSeconds(int instanceStaleSeconds) {
        this.instanceStaleSeconds = instanceStaleSeconds;
    }
}
