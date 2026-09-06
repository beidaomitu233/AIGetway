package com.lightai.client.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Trace 详情 Attempt 项（BE-032；字段对齐 FE-027 Attempt 面板）。
 * credential_id/credential_name/credential_masked_value 与 provider_request_id
 * 由服务端按字段权限序列化前裁剪（C-012），不是由前端隐藏。
 * 价格与金额为调用时快照的定点数值（字符串传输）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TraceAttemptItem(
        int sequence,
        String attemptType,
        String status,
        String providerNameSnapshot,
        String providerModelNameSnapshot,
        String modelIdSnapshot,
        String credentialNameSnapshot,
        String credentialMaskedValue,
        OffsetDateTime startedAt,
        OffsetDateTime providerStartedAt,
        OffsetDateTime responseHeadersAt,
        OffsetDateTime firstTokenAt,
        OffsetDateTime endedAt,
        Integer dispatchMs,
        Integer responseHeaderMs,
        Integer firstTokenMs,
        Integer totalMs,
        String endpointHost,
        Integer httpStatus,
        String providerRequestId,
        Boolean responseCommitted,
        String finishReason,
        String errorCode,
        String errorCategory,
        String errorStage,
        String errorSummary,
        boolean retryable,
        Integer retryAfterMs,
        Map<String, Object> resolvedParameters,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        String usageSource,
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        Integer priceUnit,
        String currency,
        BigDecimal inputCost,
        BigDecimal outputCost,
        BigDecimal totalCost) {
}
