package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** POST /admin/runtime-config/retention-impact 结果：票据绑定目标值与 revision，10 分钟有效。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RetentionImpactResult(
        @JsonProperty("impact_version") String impactVersion,
        @JsonProperty("estimated_at") OffsetDateTime estimatedAt,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("target_values") TargetValues targetValues,
        @JsonProperty("counts") Counts counts,
        @JsonProperty("earliest_remaining_at") OffsetDateTime earliestRemainingAt) {

    public record TargetValues(
            @JsonProperty("trace_retention_days") int traceRetentionDays,
            @JsonProperty("usage_retention_days") int usageRetentionDays,
            @JsonProperty("audit_retention_days") int auditRetentionDays,
            @JsonProperty("diagnostic_sample_retention_days") int diagnosticSampleRetentionDays) {
    }

    public record Counts(
            @JsonProperty("trace") long trace,
            @JsonProperty("usage") long usage,
            @JsonProperty("audit") long audit,
            @JsonProperty("sample") long sample) {
    }
}
