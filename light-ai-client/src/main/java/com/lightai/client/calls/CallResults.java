package com.lightai.client.calls;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lightai.client.trace.TraceAttemptItem;
import com.lightai.client.trace.TraceRequestSummary;
import com.lightai.client.trace.TraceSubEntityItems.CapacityReservationItem;
import com.lightai.client.trace.TraceSubEntityItems.CircuitEventItem;
import com.lightai.client.trace.TraceSubEntityItems.QueueEntryItem;
import com.lightai.client.trace.TraceSubEntityItems.RecoveryDecisionItem;
import com.lightai.client.trace.TraceSubEntityItems.RouteDecisionItem;
import com.lightai.client.trace.TraceTimelineItem;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * V2 调用记录契约（BE-231；PRD 9.7）。request_id 与既有 trace.trace_id 一一对应，
 * 是 /v1 调用返回的 X-Request-Id，也是筛选与详情定位键。
 * 列表与详情默认不含消息正文、client_ip、认证头与任何密钥信息；
 * 凭证掩码与诊断样本沿用既有字段权限，在服务端裁剪。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public final class CallResults {

    private CallResults() {
    }

    /** 调用列表项：开始时间、应用、虚拟模型、最终渠道、状态、尝试数、Token、金额与耗时。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CallListItem(
            String requestId,
            OffsetDateTime startedAt,
            String applicationCode,
            String virtualModelCode,
            String finalChannelName,
            String finalUpstreamModelName,
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
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal totalCost,
            String currency,
            String errorCode) {
    }

    /**
     * 调用详情：时间线组合准入（queue/reservation）、路由、Attempt、恢复、
     * 流式提交（response_committed 与 ATTEMPT_FIRST_TOKEN）、结算与终态。
     * request_summary 仅承载结构化摘要，消息正文只随受控诊断样本出现。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CallDetail(
            String requestId,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            Long totalMs,
            Long firstTokenMs,
            String applicationCode,
            String virtualModelCode,
            String finalChannelName,
            String finalUpstreamModelName,
            String finishReason,
            String errorCode,
            String errorSummary,
            int attemptCount,
            int retryCount,
            int credentialFailoverCount,
            int fallbackCount,
            long queuedMs,
            boolean requestedStream,
            boolean responseCommitted,
            long configSnapshotNo,
            String usageSource,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal inputCost,
            BigDecimal outputCost,
            BigDecimal totalCost,
            String currency,
            TraceRequestSummary requestSummary,
            List<TraceAttemptItem> attempts,
            List<RouteDecisionItem> routeDecisions,
            List<QueueEntryItem> queueEntries,
            List<CapacityReservationItem> capacityReservations,
            List<RecoveryDecisionItem> recoveryDecisions,
            List<CircuitEventItem> circuitEvents,
            List<TraceTimelineItem> timeline,
            OffsetDateTime detailExpiresAt) {
    }
}
