package com.lightai.client.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Trace 列表项（BACKEND_PLAN 4.4.4.1，BE-031；字段对齐 FE-025/026）。
 * RUNNING/QUEUED 的 total_ms 由服务端按当前时间在响应层计算，不回写数据库；
 * 不含 client_ip、user_agent、消息正文与任何密钥信息。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TraceListItem(
        String traceId,
        OffsetDateTime startedAt,
        String sourceMode,
        String accessCredentialName,
        String application,
        String project,
        String tenant,
        String alias,
        String finalProviderName,
        String finalProviderModelName,
        Boolean requestedStream,
        String status,
        boolean anomalousRunning,
        int attemptCount,
        int retryCount,
        int credentialFailoverCount,
        int fallbackCount,
        long queuedMs,
        Long firstTokenMs,
        Long totalMs,
        String usageSource,
        long totalTokens,
        BigDecimal totalCost,
        String currency,
        String errorCode) {
}
