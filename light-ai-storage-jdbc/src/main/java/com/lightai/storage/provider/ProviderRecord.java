package com.lightai.storage.provider;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * provider 表行（DATABASE_PLAN §1，存储类别 C）。
 * name 活行全局唯一（2—64）；default_headers 为非认证头键值。
 */
public record ProviderRecord(
        UUID id,
        String name,
        String type,
        String baseUrl,
        String proxyUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        Map<String, String> defaultHeaders,
        boolean enabled,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public ProviderRecord {
        defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
    }
}
