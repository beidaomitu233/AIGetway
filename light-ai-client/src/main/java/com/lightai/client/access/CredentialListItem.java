package com.lightai.client.access;

import java.time.OffsetDateTime;

/**
 * 池下 Credential 列表项（FRONTEND_PLAN 4.2.4.2）。
 * masked_value 为服务端脱敏值；健康与检测时间来自 object_runtime_state 组合，不进入草稿。
 */
public record CredentialListItem(
        String id,
        String poolId,
        String name,
        String secretSource,
        String maskedValue,
        Integer weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        String healthStatus,
        OffsetDateTime rateLimitResetAt,
        OffsetDateTime lastSuccessAt,
        OffsetDateTime lastCheckAt,
        Boolean enabled,
        Boolean draftChanged,
        long version,
        OffsetDateTime updatedAt) {
}
