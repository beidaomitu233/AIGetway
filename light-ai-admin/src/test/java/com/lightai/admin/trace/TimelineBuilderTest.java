package com.lightai.admin.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.client.trace.TraceTimelineItem;
import com.lightai.storage.trace.ObservationRows.AttemptRow;
import com.lightai.storage.trace.ObservationRows.CircuitEventRow;
import com.lightai.storage.trace.ObservationRows.QueueEntryRow;
import com.lightai.storage.trace.ObservationRows.RecoveryDecisionRow;
import com.lightai.storage.trace.ObservationRows.RouteDecisionRow;
import com.lightai.storage.trace.ObservationRows.TraceRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * BE-032 时间线测试：occurred_at 升序、同时间固定优先级（Trace、Queue、Route、
 * Attempt Started、First Token、Ended、Recovery、Circuit、Trace Ended）、来源 sequence。
 */
class TimelineBuilderTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-06T00:00:00Z");

    private TraceRow trace(OffsetDateTime endedAt) {
        return new TraceRow(
                UUID.randomUUID(), "trace-1", "app", null, null, Map.of(),
                "EMBEDDED", "APPLICATION", UUID.randomUUID(), "alias",
                1L, false, false, "SUCCEEDED", T0, T0.plusMinutes(1), endedAt,
                60000, null, 0L, 1, 0, 0, 0, null, null, null, null, null,
                null, null, null, 0, 0, 0, 0, 0, 0, null,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                "USD", "stop", null, null, null, null, false, Map.of(), null, null,
                T0);
    }

    private AttemptRow attempt(OffsetDateTime startedAt, OffsetDateTime firstToken,
                               OffsetDateTime endedAt) {
        return new AttemptRow(
                UUID.randomUUID(), "trace-1", 1, "INITIAL", null, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "p", "pm", "mid", "cred", "SUCCEEDED", startedAt, null, null,
                firstToken, endedAt, null, null, null, null, "host", 200, null,
                false, "stop", null, null, null, null, false, null, Map.of(),
                0, 0, 0, "ACTUAL", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                1000000, "USD", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, endedAt);
    }

    @Test
    void ordersByTimeThenFixedPriorityThenSequence() {
        // Attempt 结束与恢复决策、熔断事件同刻（31 秒），验证固定优先级排序
        AttemptRow attempt = attempt(T0.plusSeconds(10), T0.plusSeconds(20),
                T0.plusSeconds(31));
        RouteDecisionRow decision = new RouteDecisionRow(UUID.randomUUID(), "trace-1", 1,
                UUID.randomUUID(), "SELECTED", "CAPABILITY_MATCH", null, "AVAILABLE",
                T0.plusSeconds(5));
        RecoveryDecisionRow recovery = new RecoveryDecisionRow(UUID.randomUUID(), "trace-1", 1,
                attempt.id(), "RETRY", "PROVIDER_5XX", 1000, UUID.randomUUID(), null,
                1, 0, 0, 50000, T0.plusSeconds(31));
        CircuitEventRow circuit = new CircuitEventRow(UUID.randomUUID(), UUID.randomUUID(),
                "CLOSED", "OPEN", "AUTO_THRESHOLD", "PROVIDER_5XX", "reason",
                T0.plusSeconds(31));

        List<TraceTimelineItem> timeline = TimelineBuilder.build(
                trace(T0.plusSeconds(40)), List.of(attempt), List.of(decision), List.of(),
                List.of(recovery), List.of(circuit));

        List<String> types = timeline.stream().map(TraceTimelineItem::type).toList();
        assertThat(types).startsWith("TRACE_CREATED").endsWith("TRACE_ENDED");
        assertThat(types).containsSubsequence("TRACE_CREATED", "ROUTE_DECISION",
                "ATTEMPT_STARTED", "ATTEMPT_FIRST_TOKEN", "ATTEMPT_ENDED",
                "RECOVERY_DECIDED", "TRACE_ENDED");

        // 同一时刻（31 秒）的 ATTEMPT_ENDED(5) < RECOVERY_DECIDED(6) < CIRCUIT_CHANGED(7)
        List<TraceTimelineItem> at31 = timeline.stream()
                .filter(item -> item.occurredAt() != null
                        && item.occurredAt().toEpochSecond() == T0.plusSeconds(31).toEpochSecond())
                .toList();
        assertThat(at31.stream().map(TraceTimelineItem::type).toList())
                .containsExactly("ATTEMPT_ENDED", "RECOVERY_DECIDED", "CIRCUIT_CHANGED");

        // attempt_id 与 reason_code 贯穿节点
        TraceTimelineItem started = timeline.stream()
                .filter(item -> "ATTEMPT_STARTED".equals(item.type())).findFirst().orElseThrow();
        assertThat(started.attemptId()).isEqualTo(attempt.id().toString());
        assertThat(started.sequence()).isEqualTo(1L);
        TraceTimelineItem recovered = timeline.stream()
                .filter(item -> "RECOVERY_DECIDED".equals(item.type())).findFirst().orElseThrow();
        assertThat(recovered.reasonCode()).isEqualTo("PROVIDER_5XX");
    }

    @Test
    void queueNodesOnlyForRelevantStatuses() {
        QueueEntryRow waiting = new QueueEntryRow(UUID.randomUUID(), "trace-1",
                UUID.randomUUID(), 7L, List.of("policy-1"), 500L, "WAITING",
                T0.plusSeconds(2), T0.plusSeconds(30), null, null, null, null);
        QueueEntryRow acquired = new QueueEntryRow(UUID.randomUUID(), "trace-1",
                UUID.randomUUID(), 8L, List.of(), 500L, "ACQUIRED",
                T0.plusSeconds(2), T0.plusSeconds(30), T0.plusSeconds(6), null, null, null);

        List<TraceTimelineItem> timeline = TimelineBuilder.build(trace(null), List.of(),
                List.of(), List.of(waiting, acquired), List.of(), List.of());

        assertThat(timeline.stream().map(TraceTimelineItem::type))
                .containsExactly("TRACE_CREATED", "QUEUE_ENTERED", "QUEUE_ENTERED",
                        "QUEUE_ACQUIRED");
        assertThat(timeline.get(1).sequence()).isEqualTo(7L);
        assertThat(timeline.get(2).sequence()).isEqualTo(8L);
    }

    @Test
    void runningTraceHasNoTraceEndedNode() {
        List<TraceTimelineItem> timeline = TimelineBuilder.build(trace(null), List.of(),
                List.of(), List.of(), List.of(), List.of());
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).type()).isEqualTo("TRACE_CREATED");
    }
}
