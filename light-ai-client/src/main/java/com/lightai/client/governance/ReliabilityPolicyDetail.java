package com.lightai.client.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.math.BigDecimal;

/** 可靠性策略详情（BACKEND_PLAN 4.3.2）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReliabilityPolicyDetail(
        String id,
        String name,
        String aliasId,
        Integer connectTimeoutMs,
        Integer firstTokenTimeoutMs,
        Integer totalTimeoutMs,
        Integer maxRetries,
        Integer maxCredentialFailovers,
        Integer initialBackoffMs,
        BigDecimal backoffMultiplier,
        Integer jitterPercent,
        Boolean respectRetryAfter,
        Integer maxRetryAfterMs,
        Boolean fallbackEnabled,
        Integer maxFallbacks,
        Integer circuitWindowSeconds,
        Integer circuitMinRequests,
        BigDecimal circuitFailureRate,
        Integer circuitOpenSeconds,
        Integer circuitHalfOpenProbes,
        Integer circuitHalfOpenSuccesses,
        boolean enabled,
        boolean draftChanged,
        long version,
        OffsetDateTime updatedAt) {

    /** 系统默认策略（SYSTEM_DEFAULT，不允许直接编辑）。 */
    public static ReliabilityPolicyDetail systemDefault() {
        return new ReliabilityPolicyDetail("SYSTEM_DEFAULT", "系统默认策略", null,
                3000, 30000, 120000, 1, 1, 200, new BigDecimal("2.00"), 20, true, 5000,
                true, 2, 60, 20, new BigDecimal("0.5000"), 30, 3, 2,
                true, false, 0, null);
    }
}
