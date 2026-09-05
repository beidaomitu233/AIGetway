package com.lightai.runtime.circuit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 熔断验收（BE-023）：429 不计失败、阈值→OPEN、OPEN 到期→HALF_OPEN、
 * 探测名额不超额、探测成功达数→CLOSED、人工命令 CAS 竞态。
 */
class InMemoryCircuitStoreTest {

    private InMemoryCircuitStore store;
    private final CircuitKey key = new CircuitKey(UUID.randomUUID(), UUID.randomUUID());
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

    private static final CircuitPolicy POLICY =
            new CircuitPolicy(UUID.randomUUID(), 3, 60, 5, 0.5, 30, 2, 1);

    private final List<CircuitStateStore.CircuitEventPayload> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new InMemoryCircuitStore();
        store.addListener(events::add);
    }

    @Test
    void throttledRequestsDoNotCountAsFailures() {
        // 20 次全 429：不计失败，CLOSED 保持
        for (int i = 0; i < 20; i++) {
            store.recordResult(key, POLICY, false, true, T0.plusSeconds(i));
        }
        var snapshot = store.snapshot(key, POLICY, T0.plusSeconds(30));
        assertThat(snapshot.state()).isEqualTo(CircuitSnapshot.STATE_CLOSED);
        assertThat(snapshot.requestCount()).isZero();
        assertThat(snapshot.failureCount()).isZero();
    }

    @Test
    void thresholdOpensCircuitAutomatically() {
        // 5 次中 3 次失败：失败率 0.6 ≥ 0.5 → OPEN
        store.recordResult(key, POLICY, true, false, T0);
        store.recordResult(key, POLICY, false, false, T0.plusSeconds(1));
        store.recordResult(key, POLICY, false, false, T0.plusSeconds(2));
        store.recordResult(key, POLICY, false, false, T0.plusSeconds(3));
        store.recordResult(key, POLICY, true, false, T0.plusSeconds(4));
        var snapshot = store.snapshot(key, POLICY, T0.plusSeconds(5));
        assertThat(snapshot.state()).isEqualTo(CircuitSnapshot.STATE_OPEN);
        assertThat(snapshot.openSource()).isEqualTo("AUTO");
        assertThat(events).anySatisfy(event ->
                assertThat(event.triggerType()).isEqualTo("AUTO_THRESHOLD"));
    }

    @Test
    void openExpiresToHalfOpenAndProbeSuccessCloses() {
        store.recordResult(key, POLICY, false, false, T0);
        store.recordResult(key, POLICY, false, false, T0.plusSeconds(1));
        store.recordResult(key, POLICY, false, false, T0.plusSeconds(2));
        store.recordResult(key, POLICY, false, false, T0.plusSeconds(3));
        store.recordResult(key, POLICY, false, false, T0.plusSeconds(4));
        assertThat(store.snapshot(key, POLICY, T0.plusSeconds(5)).state())
                .isEqualTo(CircuitSnapshot.STATE_OPEN);

        // open_seconds=30 到期 → HALF_OPEN，探测名额 2
        var afterOpen = store.snapshot(key, POLICY, T0.plusSeconds(35));
        assertThat(afterOpen.state()).isEqualTo(CircuitSnapshot.STATE_HALF_OPEN);

        // 探测成功 1 次（half_open_successes=1）→ CLOSED
        var slot = store.tryAcquireProbe(key, POLICY, T0.plusSeconds(35)).orElseThrow();
        store.recordResult(key, POLICY, true, false, T0.plusSeconds(36));
        store.releaseProbe(slot, key, true, T0.plusSeconds(36));
        var closed = store.snapshot(key, POLICY, T0.plusSeconds(37));
        assertThat(closed.state()).isEqualTo(CircuitSnapshot.STATE_CLOSED);
        assertThat(closed.requestCount()).isZero();
    }

    @Test
    void probeSlotsBoundedInHalfOpen() {
        CircuitPolicy multiProbe = new CircuitPolicy(UUID.randomUUID(), 3, 60, 5, 0.5, 30, 2, 2);
        store.recordResult(key, multiProbe, false, false, T0);
        store.recordResult(key, multiProbe, false, false, T0.plusSeconds(1));
        store.recordResult(key, multiProbe, false, false, T0.plusSeconds(2));
        store.recordResult(key, multiProbe, false, false, T0.plusSeconds(3));
        store.recordResult(key, multiProbe, false, false, T0.plusSeconds(4));

        var halfOpenAt = T0.plusSeconds(35);
        assertThat(store.snapshot(key, multiProbe, halfOpenAt).state())
                .isEqualTo(CircuitSnapshot.STATE_HALF_OPEN);
        assertThat(store.tryAcquireProbe(key, multiProbe, halfOpenAt)).isPresent();
        assertThat(store.tryAcquireProbe(key, multiProbe, halfOpenAt)).isPresent();
        // 名额 2 已满：第三个探测拒绝
        assertThat(store.tryAcquireProbe(key, multiProbe, halfOpenAt)).isEmpty();
    }

    @Test
    void manualOpenRequiresMatchingStateVersion() {
        var before = store.snapshot(key, POLICY, T0);
        long wrongVersion = before.stateVersion() + 5;
        var mismatch = store.applyManualCommand(key, POLICY, new CircuitStateStore.ManualCommand(
                UUID.randomUUID(), CircuitStateStore.ManualCommand.Action.MANUAL_OPEN,
                wrongVersion, "人工处理", 60, T0), T0.plusSeconds(1));
        assertThat(mismatch).isEmpty();

        var applied = store.applyManualCommand(key, POLICY, new CircuitStateStore.ManualCommand(
                UUID.randomUUID(), CircuitStateStore.ManualCommand.Action.MANUAL_OPEN,
                before.stateVersion(), "人工处理", 60, T0), T0.plusSeconds(1));
        assertThat(applied).isPresent();
        assertThat(applied.orElseThrow().state()).isEqualTo(CircuitSnapshot.STATE_OPEN);
        assertThat(applied.orElseThrow().openSource()).isEqualTo("MANUAL");
        assertThat(events).anySatisfy(event ->
                assertThat(event.triggerType()).isEqualTo("MANUAL_OPEN"));
    }

    @Test
    void manualRecoverReturnsToClosed() {
        store.applyManualCommand(key, POLICY, new CircuitStateStore.ManualCommand(
                UUID.randomUUID(), CircuitStateStore.ManualCommand.Action.MANUAL_OPEN,
                store.snapshot(key, POLICY, T0).stateVersion(), "人工", 60, T0), T0);
        var snapshot = store.snapshot(key, POLICY, T0.plusSeconds(1));
        var recovered = store.applyManualCommand(key, POLICY, new CircuitStateStore.ManualCommand(
                UUID.randomUUID(), CircuitStateStore.ManualCommand.Action.MANUAL_RECOVER,
                snapshot.stateVersion(), "已恢复", null, T0.plusSeconds(1)), T0.plusSeconds(2));
        assertThat(recovered).isPresent();
        assertThat(recovered.orElseThrow().state()).isEqualTo(CircuitSnapshot.STATE_CLOSED);
    }
}
