package com.lightai.client.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * 可靠性策略命令（BACKEND_PLAN 4.3.2）。
 * 边界与 DATABASE_PLAN reliability_policy 一致；同一 alias 至多一条启用（冲突
 * 返回 RELIABILITY_POLICY_CONFLICT）；fallback_enabled=false 强制 max_fallbacks=0。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReliabilityPolicySaveCommand(
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
        Long version) {

    public void validate() {
        if (name == null || name.strip().length() < 2 || name.strip().length() > 64) {
            throw new IllegalArgumentException("name 长度必须为 2—64");
        }
        if (aliasId == null || aliasId.isBlank()) {
            throw new IllegalArgumentException("alias_id 必填");
        }
        range(connectTimeoutMs, 100, 60000, "connect_timeout_ms");
        range(firstTokenTimeoutMs, 1000, 300000, "first_token_timeout_ms");
        range(totalTimeoutMs, 1000, 600000, "total_timeout_ms");
        if (firstTokenTimeoutMs != null && totalTimeoutMs != null
                && firstTokenTimeoutMs >= totalTimeoutMs) {
            throw new IllegalArgumentException("first_token_timeout_ms 必须小于 total_timeout_ms");
        }
        range(maxRetries, 0, 5, "max_retries");
        range(maxCredentialFailovers, 0, 5, "max_credential_failovers");
        range(initialBackoffMs, 0, 10000, "initial_backoff_ms");
        if (backoffMultiplier != null
                && (backoffMultiplier.doubleValue() < 1 || backoffMultiplier.doubleValue() > 5)) {
            throw new IllegalArgumentException("backoff_multiplier 范围 1—5");
        }
        range(jitterPercent, 0, 100, "jitter_percent");
        range(maxRetryAfterMs, 0, 60000, "max_retry_after_ms");
        boolean fallback = fallbackEnabled == null || fallbackEnabled;
        range(maxFallbacks, fallback ? 0 : 0, 10, "max_fallbacks");
        if (!fallback && maxFallbacks != null && maxFallbacks != 0) {
            throw new IllegalArgumentException("fallback_enabled=false 时 max_fallbacks 必须为 0");
        }
        range(circuitWindowSeconds, 10, 600, "circuit_window_seconds");
        range(circuitMinRequests, 1, 10000, "circuit_min_requests");
        if (circuitFailureRate != null && (circuitFailureRate.doubleValue() < 0.01
                || circuitFailureRate.doubleValue() > 1)) {
            throw new IllegalArgumentException("circuit_failure_rate 范围 0.01—1");
        }
        range(circuitOpenSeconds, 1, 3600, "circuit_open_seconds");
        range(circuitHalfOpenProbes, 1, 100, "circuit_half_open_probes");
        range(circuitHalfOpenSuccesses, 1, 100, "circuit_half_open_successes");
        if (circuitHalfOpenSuccesses != null && circuitHalfOpenProbes != null
                && circuitHalfOpenSuccesses > circuitHalfOpenProbes) {
            throw new IllegalArgumentException("half_open_successes 不超过 half_open_probes");
        }
    }

    private static void range(Integer value, int min, int max, String field) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(field + " 范围 " + min + "—" + max);
        }
    }
}
