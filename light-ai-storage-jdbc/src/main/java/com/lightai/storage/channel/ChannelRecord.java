package com.lightai.storage.channel;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * channel 表行（DATABASE_PLAN §1，存储类别 C）。
 * V2 语义：渠道承接连接语义，providerType 来自协议类型目录（provider.type，联表读出，非写入列）；
 * name 活行全局唯一（2—64）；default_headers 为非认证头键值。
 */
public record ChannelRecord(
        UUID id,
        UUID providerId,
        String providerType,
        String name,
        String baseUrl,
        String proxyUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        int streamIdleTimeoutMs,
        Map<String, String> defaultHeaders,
        int priority,
        int weight,
        String status,
        String health,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String HEALTH_UNKNOWN = "UNKNOWN";

    public ChannelRecord {
        defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
    }

    /** 兼容读取：status == ACTIVE 视为启用。 */
    public boolean enabled() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }
}
