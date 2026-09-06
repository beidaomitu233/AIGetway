package com.lightai.client.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Trace 摘要对象（BACKEND_PLAN 4.4.4.1，BE-032；字段对齐 FE-027/029/030）。
 * total_* 汇总全部产生用量或费用的 Attempt；response_* 只来自最终成功 Attempt，
 * 用于说明调用方收到的 Usage（对账口径 RV-017/038）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TraceSummary(
        String traceId,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        Long totalMs,
        String application,
        String project,
        String tenant,
        Map<String, String> tags,
        String aliasId,
        String alias,
        long configSnapshotNo,
        String sourceMode,
        Boolean requestedStream,
        boolean responseCommitted,
        String finishReason,
        int attemptCount,
        int retryCount,
        int credentialFailoverCount,
        int fallbackCount,
        long queuedMs,
        Long firstTokenMs,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long responseInputTokens,
        long responseOutputTokens,
        long responseTotalTokens,
        String usageSource,
        BigDecimal inputCost,
        BigDecimal outputCost,
        BigDecimal totalCost,
        String currency,
        String errorCode,
        String errorSummary,
        String finalAttemptId,
        List<String> failedAttemptIds) {
}
