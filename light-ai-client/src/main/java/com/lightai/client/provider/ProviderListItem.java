package com.lightai.client.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Provider 列表项（BACKEND_PLAN 4.2.9.1；字段对齐 FE-007）。
 * connection_status 等运行状态由 object_runtime_state 组合派生，
 * 不进入草稿 DTO；不含任何密钥信息。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProviderListItem(
        String id,
        String name,
        String type,
        String baseUrl,
        String proxyUrl,
        String connectionStatus,
        OffsetDateTime lastCheckAt,
        Long lastCheckLatencyMs,
        String lastErrorCode,
        long providerModelCount,
        long credentialPoolCount,
        boolean enabled,
        boolean draftChanged,
        long version,
        OffsetDateTime updatedAt) {
}
