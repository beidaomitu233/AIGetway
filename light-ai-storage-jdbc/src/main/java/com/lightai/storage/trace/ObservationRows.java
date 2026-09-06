package com.lightai.storage.trace;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 观测表行记录（DATABASE_PLAN trace/attempt，第 15/16 表；R 类运行事实）。
 * 仅承载观测读路径需要的列；金额为定点数值，价格/金额不做浮点换算。
 */
public final class ObservationRows {

    private ObservationRows() {
    }

    /** trace 行（DATABASE_PLAN 第 15 表）。 */
    public record TraceRow(
            UUID id,
            String traceId,
            String application,
            String project,
            String tenant,
            Map<String, String> tags,
            String sourceMode,
            String invocationSource,
            UUID aliasId,
            String alias,
            long configSnapshotNo,
            boolean requestedStream,
            boolean responseCommitted,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime deadlineAt,
            OffsetDateTime endedAt,
            Integer totalMs,
            Integer firstTokenMs,
            long queuedMs,
            int attemptCount,
            int retryCount,
            int credentialFailoverCount,
            int fallbackCount,
            UUID finalAttemptId,
            UUID finalProviderId,
            UUID finalProviderModelId,
            UUID finalCredentialId,
            UUID accessCredentialId,
            String finalProviderName,
            String finalProviderModelName,
            String accessCredentialName,
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
            String finishReason,
            String errorCode,
            String errorCategory,
            String errorStage,
            String errorSummary,
            boolean retryable,
            Map<String, Object> requestSummary,
            String clientIp,
            String userAgent,
            OffsetDateTime updatedAt) {
    }

    /** attempt 行（DATABASE_PLAN 第 16 表；settled_at 非空表示已完成结算）。 */
    public record AttemptRow(
            UUID id,
            String traceId,
            int sequence,
            String attemptType,
            UUID routeCandidateId,
            UUID providerId,
            UUID providerModelId,
            UUID credentialPoolId,
            UUID credentialId,
            String providerNameSnapshot,
            String providerModelNameSnapshot,
            String modelIdSnapshot,
            String credentialNameSnapshot,
            String status,
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
            boolean responseCommitted,
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
            int priceUnit,
            String currency,
            BigDecimal inputCost,
            BigDecimal outputCost,
            BigDecimal totalCost,
            OffsetDateTime settledAt) {
    }

    /** route_decision 行（第 18 表，I 类不可变事实）。 */
    public record RouteDecisionRow(
            UUID id,
            String traceId,
            int sequence,
            UUID routeCandidateId,
            String decision,
            String reasonCode,
            String reasonDetail,
            String observedStatus,
            OffsetDateTime createdAt) {
    }

    /** queue_entry 行（第 21 表）。 */
    public record QueueEntryRow(
            UUID id,
            String traceId,
            UUID aliasId,
            long sequence,
            java.util.List<String> blockingPolicyIds,
            long estimatedTokens,
            String status,
            OffsetDateTime enqueuedAt,
            OffsetDateTime deadlineAt,
            OffsetDateTime acquiredAt,
            OffsetDateTime endedAt,
            String wakeReason,
            String errorCode) {
    }

    /** capacity_reservation 行（第 19 表）。 */
    public record ReservationRow(
            UUID id,
            String traceId,
            UUID attemptId,
            String status,
            long reservedTokens,
            Long actualTokens,
            OffsetDateTime createdAt,
            OffsetDateTime settledAt,
            String releaseReason) {
    }

    /** capacity_reservation_item 行（第 20 表，I 类）。 */
    public record ReservationItemRow(
            UUID id,
            UUID reservationId,
            UUID scopeId,
            String scopeType,
            java.util.List<String> policyIds) {
    }

    /** recovery_decision 行（第 22 表，I 类）。 */
    public record RecoveryDecisionRow(
            UUID id,
            String traceId,
            int sequence,
            UUID sourceAttemptId,
            String action,
            String reasonCode,
            int scheduledDelayMs,
            UUID targetRouteCandidateId,
            UUID targetCredentialId,
            int retriesUsed,
            int credentialFailoversUsed,
            int fallbacksUsed,
            int remainingTimeoutMs,
            OffsetDateTime createdAt) {
    }

    /** circuit_event 行（第 24 表，I 类）。 */
    public record CircuitEventRow(
            UUID id,
            UUID circuitId,
            String fromState,
            String toState,
            String triggerType,
            String errorCode,
            String reason,
            OffsetDateTime occurredAt) {
    }

    /** trace_content_sample 行（第 17 表）。 */
    public record ContentSampleRow(
            UUID id,
            String traceId,
            String sampledMessagesJson,
            String sampledResponse,
            String redactionVersion,
            OffsetDateTime expiresAt) {
    }
}
