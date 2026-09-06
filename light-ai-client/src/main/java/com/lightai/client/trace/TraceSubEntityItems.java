package com.lightai.client.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Trace 详情子集合项（BE-032；字段对齐 FE-027/028/029）。
 * 集合 JSON 键名为 route_decisions/queue_entries/capacity_reservations/
 * recovery_decisions/circuit_events（snake_case 自动映射）。
 */
public final class TraceSubEntityItems {

    private TraceSubEntityItems() {
    }

    /** 路由决策：按 sequence 排序展示候选过滤、选择与失败原因。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RouteDecisionItem(
            int sequence,
            String routeCandidateId,
            String decision,
            String reasonCode,
            String reasonDetail,
            String observedStatus,
            OffsetDateTime createdAt) {
    }

    /** FIFO 队列等待事实；未排队时服务端不生成时间线节点。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record QueueEntryItem(
            Long sequence,
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

    /** 容量预占：policy_ids 为三层维度明细聚合；EXPIRED 异常明确标记。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CapacityReservationItem(
            String id,
            String attemptId,
            java.util.List<String> policyIds,
            long reservedTokens,
            Long actualTokens,
            String status,
            String releaseReason,
            OffsetDateTime createdAt,
            OffsetDateTime settledAt) {
    }

    /** 恢复决策：置于来源 Attempt 结束与目标 Attempt 开始之间（FE-029）。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecoveryDecisionItem(
            int sequence,
            String sourceAttemptId,
            String action,
            String reasonCode,
            int scheduledDelayMs,
            String targetRouteCandidateId,
            String targetCredentialId,
            int retriesUsed,
            int credentialFailoversUsed,
            int fallbacksUsed,
            int remainingTimeoutMs,
            OffsetDateTime createdAt) {
    }

    /** 熔断事件：只返回 trigger_trace_id 等于本 Trace 的事件。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CircuitEventItem(
            String circuitId,
            String fromState,
            String toState,
            String triggerType,
            String errorCode,
            String reason,
            OffsetDateTime createdAt) {
    }
}
