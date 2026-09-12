package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 渠道列表项（BACKEND_PLAN BE-211：status/health 分列，priority/weight 可见）。
 * status 为配置状态（ACTIVE/DISABLED），health 由 object_runtime_state 派生
 * （UNKNOWN/AVAILABLE/UNAVAILABLE），不进入草稿 DTO；不含任何密钥信息。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChannelListItem(
        String id,
        String name,
        String providerType,
        String baseUrl,
        String proxy,
        String status,
        String health,
        int priority,
        int weight,
        long upstreamModelCount,
        long credentialCount,
        boolean draftChanged,
        OffsetDateTime lastCheckedAt,
        Long lastCheckLatencyMs,
        String lastErrorCode,
        long version,
        OffsetDateTime updatedAt) {
}
