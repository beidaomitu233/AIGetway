package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 留存影响估算请求（FE-044 / BE-043）：四个维度的目标天数。
 */
public record RetentionImpactCommand(
        @JsonProperty("trace_retention_days") int traceRetentionDays,
        @JsonProperty("usage_retention_days") int usageRetentionDays,
        @JsonProperty("audit_retention_days") int auditRetentionDays,
        @JsonProperty("diagnostic_sample_retention_days") int diagnosticSampleRetentionDays) {
}
