package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * PUT /admin/runtime-config 命令（BE-043）：提交完整可编辑对象与 version；
 * 缩短留存时 confirmed_impact_version 必填（票据 10 分钟有效）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RuntimeConfigUpdateCommand(
        String timezone,
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
        long version,
        String confirmedImpactVersion) {
}
