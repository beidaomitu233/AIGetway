package com.lightai.admin.trace;

import com.lightai.client.trace.TraceTimelineItem;
import com.lightai.storage.trace.ObservationRows;
import com.lightai.storage.trace.ObservationRows.AttemptRow;
import com.lightai.storage.trace.ObservationRows.CircuitEventRow;
import com.lightai.storage.trace.ObservationRows.QueueEntryRow;
import com.lightai.storage.trace.ObservationRows.RecoveryDecisionRow;
import com.lightai.storage.trace.ObservationRows.RouteDecisionRow;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 统一时间线装配（BE-032；FE-028 排序契约）。
 * 事件按 occurred_at 升序；同时间按固定优先级 Trace、Queue、RouteDecision、
 * Attempt Started、Attempt First Token、Attempt Ended、RecoveryDecision、CircuitEvent、
 * Trace Ended，再按来源 sequence 排序。排序由服务端实现，前端不得重排。
 */
public final class TimelineBuilder {

    private static final Comparator<TraceTimelineItem> ORDER = Comparator
            .comparing(TraceTimelineItem::occurredAt,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(item -> TraceTimelineItem.orderOfType(item.type()))
            .thenComparing(item -> item.sequence() == null ? Long.MIN_VALUE : item.sequence())
            .thenComparing(item -> item.sourceId() == null ? "" : item.sourceId());

    private TimelineBuilder() {
    }

    public static List<TraceTimelineItem> build(
            ObservationRows.TraceRow trace,
            List<AttemptRow> attempts,
            List<RouteDecisionRow> routeDecisions,
            List<QueueEntryRow> queueEntries,
            List<RecoveryDecisionRow> recoveryDecisions,
            List<CircuitEventRow> circuitEvents) {
        List<TraceTimelineItem> items = new ArrayList<>();
        items.add(new TraceTimelineItem("TRACE_CREATED:" + trace.traceId(), "TRACE_CREATED",
                trace.startedAt(), trace.traceId(), null, null, null));

        for (QueueEntryRow entry : queueEntries) {
            String id = "QUEUE_ENTERED:" + entry.id();
            items.add(new TraceTimelineItem(id, "QUEUE_ENTERED", entry.enqueuedAt(),
                    entry.id().toString(), entry.sequence(), null, entry.errorCode()));
            if ("ACQUIRED".equals(entry.status()) && entry.acquiredAt() != null) {
                items.add(new TraceTimelineItem("QUEUE_ACQUIRED:" + entry.id(), "QUEUE_ACQUIRED",
                        entry.acquiredAt(), entry.id().toString(), entry.sequence(), null, null));
            }
            if (("TIMEOUT".equals(entry.status()) || "REJECTED".equals(entry.status())
                    || "CANCELLED".equals(entry.status())) && entry.endedAt() != null) {
                items.add(new TraceTimelineItem("QUEUE_ENDED:" + entry.id(), "QUEUE_ENDED",
                        entry.endedAt(), entry.id().toString(), entry.sequence(), null,
                        entry.errorCode() == null ? entry.wakeReason() : entry.errorCode()));
            }
        }

        for (RouteDecisionRow decision : routeDecisions) {
            items.add(new TraceTimelineItem("ROUTE_DECISION:" + decision.id(), "ROUTE_DECISION",
                    decision.createdAt(), decision.id().toString(), (long) decision.sequence(),
                    null, decision.reasonCode()));
        }

        for (AttemptRow attempt : attempts) {
            String attemptId = attempt.id().toString();
            items.add(new TraceTimelineItem("ATTEMPT_STARTED:" + attempt.id(), "ATTEMPT_STARTED",
                    attempt.startedAt(), attemptId, (long) attempt.sequence(), attemptId, null));
            if (attempt.firstTokenAt() != null) {
                items.add(new TraceTimelineItem("ATTEMPT_FIRST_TOKEN:" + attempt.id(),
                        "ATTEMPT_FIRST_TOKEN", attempt.firstTokenAt(), attemptId,
                        (long) attempt.sequence(), attemptId, null));
            }
            if (attempt.endedAt() != null) {
                items.add(new TraceTimelineItem("ATTEMPT_ENDED:" + attempt.id(), "ATTEMPT_ENDED",
                        attempt.endedAt(), attemptId, (long) attempt.sequence(), attemptId,
                        attempt.errorCode()));
            }
        }

        for (RecoveryDecisionRow recovery : recoveryDecisions) {
            items.add(new TraceTimelineItem("RECOVERY_DECIDED:" + recovery.id(), "RECOVERY_DECIDED",
                    recovery.createdAt(), recovery.id().toString(), (long) recovery.sequence(),
                    recovery.sourceAttemptId().toString(), recovery.reasonCode()));
        }

        for (CircuitEventRow event : circuitEvents) {
            items.add(new TraceTimelineItem("CIRCUIT_CHANGED:" + event.id(), "CIRCUIT_CHANGED",
                    event.occurredAt(), event.circuitId().toString(), null, null, null));
        }

        OffsetDateTime endedAt = trace.endedAt();
        if (endedAt != null) {
            items.add(new TraceTimelineItem("TRACE_ENDED:" + trace.traceId(), "TRACE_ENDED",
                    endedAt, trace.traceId(), null, null, trace.errorCode()));
        }

        items.sort(ORDER);
        return List.copyOf(items);
    }
}
