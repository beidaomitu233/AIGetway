package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** POST /admin/runtime-config/retention-impact 结果：票据绑定目标值与 revision，10 分钟有效。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RetentionImpactResult(
        String impactVersion,
        int traceRetentionDays,
        int usageRetentionDays,
        int auditRetentionDays,
        long traceCount,
        long usageCount,
        long auditCount,
        long sampleCount,
        OffsetDateTime estimatedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime earliestRemainingAt) {
}
