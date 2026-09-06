package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GET /admin/runtime-config 响应（BE-043，DATABASE_PLAN runtime_config）。
 * current_snapshot_no/published_at 为运行指针，不进入可编辑 DTO。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RuntimeConfigView(
        String timezone,
        boolean timezoneLocked,
        int traceRetentionDays,
        int usageRetentionDays,
        int auditRetentionDays,
        int dashboardRefreshSeconds,
        int maxMessageChars,
        int maxRequestChars,
        boolean diagnosticSamplingEnabled,
        java.math.BigDecimal diagnosticSampleRate,
        int diagnosticSampleRetentionDays,
        int diagnosticSampleMaxChars,
        boolean clientIpRecordingEnabled,
        java.util.List<String> trustedProxyCidrs,
        int publishInstanceTimeoutSeconds,
        int instanceStaleSeconds,
        String defaultAliasId,
        long currentSnapshotNo,
        java.time.OffsetDateTime publishedAt,
        long version) {
}
