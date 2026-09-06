package com.lightai.starter.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻享 AI Spring Boot 配置属性（PRD 4.6.3.2，BE-055）：
 * 统一在 light-ai 命名空间下定义。
 */
@ConfigurationProperties(prefix = "light-ai")
public class SpringLightAiProperties {

    /** 是否启用轻享 AI 装配（默认 true；为 false 时不创建任何 Bean） */
    private boolean enabled = true;

    /** 运行模式：STANDALONE_CLIENT 或 EMBEDDED */
    private Mode mode = Mode.STANDALONE_CLIENT;

    /** 业务应用标识（EMBEDDED 模式下必须配置） */
    private String application;

    /** Standalone 客户端配置 */
    private ClientProperties client = new ClientProperties();

    /** Embedded Admin UI 配置 */
    private AdminProperties admin = new AdminProperties();

    /** 运行实例标识配置 */
    private InstanceProperties instance = new InstanceProperties();

    /** 存储与结构配置 */
    private StorageProperties storage = new StorageProperties();

    public enum Mode {
        STANDALONE_CLIENT,
        EMBEDDED
    }

    public static class ClientProperties {
        private String baseUrl;
        private String accessToken;
        private long connectTimeoutMs = 10000L;
        private long requestTimeoutMs = 60000L;
        private long streamIdleTimeoutMs = 30000L;
        private int transportRetries = 0;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public long getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(long requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
        public long getStreamIdleTimeoutMs() { return streamIdleTimeoutMs; }
        public void setStreamIdleTimeoutMs(long streamIdleTimeoutMs) { this.streamIdleTimeoutMs = streamIdleTimeoutMs; }
        public int getTransportRetries() { return transportRetries; }
        public void setTransportRetries(int transportRetries) { this.transportRetries = transportRetries; }
    }

    public static class AdminProperties {
        private boolean enabled = true;
        private String path = "/light-ai/admin";
        private boolean localAccessEnabled = false;
        private List<String> trustedNetworkCidrs = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public boolean isLocalAccessEnabled() { return localAccessEnabled; }
        public void setLocalAccessEnabled(boolean localAccessEnabled) { this.localAccessEnabled = localAccessEnabled; }
        public List<String> getTrustedNetworkCidrs() { return trustedNetworkCidrs; }
        public void setTrustedNetworkCidrs(List<String> trustedNetworkCidrs) { this.trustedNetworkCidrs = trustedNetworkCidrs; }
    }

    public static class InstanceProperties {
        private String instanceId;
        private String zone;
        private int shutdownTimeoutSeconds = 30;

        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        public String getZone() { return zone; }
        public void setZone(String zone) { this.zone = zone; }
        public int getShutdownTimeoutSeconds() { return shutdownTimeoutSeconds; }
        public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) { this.shutdownTimeoutSeconds = shutdownTimeoutSeconds; }
    }

    public static class StorageProperties {
        private String dataSourceBeanName;
        private String redisConnectionFactoryBeanName;
        private boolean redisRequired = false;
        private String schemaMode = "VALIDATE";

        public String getDataSourceBeanName() { return dataSourceBeanName; }
        public void setDataSourceBeanName(String dataSourceBeanName) { this.dataSourceBeanName = dataSourceBeanName; }
        public String getRedisConnectionFactoryBeanName() { return redisConnectionFactoryBeanName; }
        public void setRedisConnectionFactoryBeanName(String redisConnectionFactoryBeanName) { this.redisConnectionFactoryBeanName = redisConnectionFactoryBeanName; }
        public boolean isRedisRequired() { return redisRequired; }
        public void setRedisRequired(boolean redisRequired) { this.redisRequired = redisRequired; }
        public String getSchemaMode() { return schemaMode; }
        public void setSchemaMode(String schemaMode) { this.schemaMode = schemaMode; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getApplication() { return application; }
    public void setApplication(String application) { this.application = application; }
    public ClientProperties getClient() { return client; }
    public void setClient(ClientProperties client) { this.client = client; }
    public AdminProperties getAdmin() { return admin; }
    public void setAdmin(AdminProperties admin) { this.admin = admin; }
    public InstanceProperties getInstance() { return instance; }
    public void setInstance(InstanceProperties instance) { this.instance = instance; }
    public StorageProperties getStorage() { return storage; }
    public void setStorage(StorageProperties storage) { this.storage = storage; }
}
