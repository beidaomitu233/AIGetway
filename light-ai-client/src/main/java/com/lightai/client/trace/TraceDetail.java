package com.lightai.client.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Trace 详情（BACKEND_PLAN 4.4.4.1，BE-032）。
 * 结构对齐协议字典：trace、request_summary、attempts、route_decisions、queue_entries、
 * capacity_reservations、recovery_decisions、circuit_events、timeline、detail_expires_at。
 * attempts 数量必须等于 trace.attempt_count；各子集合已按固定顺序排序；
 * 任何下级记录读取失败返回 OBSERVATION_DATA_UNAVAILABLE，不返回局部详情。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TraceDetail(
        TraceSummary trace,
        TraceRequestSummary requestSummary,
        List<TraceAttemptItem> attempts,
        List<TraceSubEntityItems.RouteDecisionItem> routeDecisions,
        List<TraceSubEntityItems.QueueEntryItem> queueEntries,
        List<TraceSubEntityItems.CapacityReservationItem> capacityReservations,
        List<TraceSubEntityItems.RecoveryDecisionItem> recoveryDecisions,
        List<TraceSubEntityItems.CircuitEventItem> circuitEvents,
        List<TraceTimelineItem> timeline,
        OffsetDateTime detailExpiresAt) {
}
