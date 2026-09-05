package com.lightai.client.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** 限流策略详情（BACKEND_PLAN 4.3.1）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LimitPolicyDetail(
        String id,
        String name,
        String scopeType,
        String scopeId,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        String overflowStrategy,
        Integer queueTimeoutMs,
        Integer queueMaxSize,
        boolean enabled,
        boolean draftChanged,
        long version,
        Long currentRpm,
        Long currentTpm,
        Integer currentConcurrent,
        Long queueLength,
        String capacityStoreStatus,
        OffsetDateTime updatedAt) {
}
