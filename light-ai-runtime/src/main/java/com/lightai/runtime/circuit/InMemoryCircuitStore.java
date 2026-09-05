package com.lightai.runtime.circuit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内熔断状态存储（BE-023）：Embedded 单实例实现，集群使用共享 Redis
 * （BE-P05 storage-redis）以相同端口替换。
 * 口径：429 与客户端取消不计失败（分类传入）；窗口计数满 min_requests 且
 * 失败率 ≥ failure_rate → OPEN（AUTO）；OPEN 到期惰性迁移 HALF_OPEN；
 * HALF_OPEN 探测名额 = half_open_probes，成功达 half_open_successes → CLOSED，
 * 任一探测失败 → OPEN。人工命令按 state_version CAS，事件以 event_key 幂等回调。
 */
public class InMemoryCircuitStore implements CircuitStateStore {

    private final Map<CircuitKey, InternalState> states = new ConcurrentHashMap<>();
    private final AtomicLong circuitIdCursor = new AtomicLong();
    private final List<EventListener> listeners = new ArrayList<>();
    private final Object eventLock = new Object();

    public void addListener(EventListener listener) {
        listeners.add(listener);
    }

    @Override
    public CircuitSnapshot snapshot(CircuitKey key, CircuitPolicy policy, Instant now) {
        InternalState state = internal(key, policy, now);
        synchronized (state) {
            lazyTransition(state, policy, now);
            return state.snapshot(policy, now);
        }
    }

    @Override
    public CircuitSnapshot recordResult(CircuitKey key, CircuitPolicy policy,
                                        boolean success, boolean throttled, Instant now) {
        InternalState state = internal(key, policy, now);
        synchronized (state) {
            lazyTransition(state, policy, now);
            state.ensureWindow(policy, now);
            // 429/限流是容量信号而非健康信号：不计请求也不计失败
            if (throttled) {
                return state.snapshot(policy, now);
            }
            state.requestCount++;
            if (!success) {
                state.failureCount++;
            }
            String from = state.state;
            if (state.state.equals(CircuitSnapshot.STATE_HALF_OPEN) && !success) {
                // HALF_OPEN 探测失败 → 立即重新 OPEN
                transition(state, policy, CircuitSnapshot.STATE_OPEN, "PROBE_FAILURE",
                        null, null, now);
            } else if (state.state.equals(CircuitSnapshot.STATE_CLOSED)
                    && state.requestCount >= policy.minRequests()) {
                double rate = state.requestCount == 0 ? 0 : (double) state.failureCount / state.requestCount;
                if (rate >= policy.failureRate()) {
                    transition(state, policy, CircuitSnapshot.STATE_OPEN, "AUTO_THRESHOLD",
                            null, "失败率 " + rate + " 达到阈值", now);
                }
            } else if (from.equals(CircuitSnapshot.STATE_HALF_OPEN) && success) {
                state.probeSuccessCount++;
                if (state.probeSuccessCount >= policy.halfOpenSuccesses()) {
                    transition(state, policy, CircuitSnapshot.STATE_CLOSED, "PROBE_SUCCESS",
                            null, null, now);
                }
            }
            return state.snapshot(policy, now);
        }
    }

    @Override
    public Optional<ProbeSlot> tryAcquireProbe(CircuitKey key, CircuitPolicy policy, Instant now) {
        InternalState state = internal(key, policy, now);
        synchronized (state) {
            lazyTransition(state, policy, now);
            if (!CircuitSnapshot.STATE_HALF_OPEN.equals(state.state)) {
                return Optional.empty();
            }
            if (state.probeInflight >= policy.halfOpenProbes()) {
                return Optional.empty(); // 名额已满：HALF_OPEN 不超额
            }
            state.probeInflight++;
            return Optional.of(new ProbeSlot(UUID.randomUUID(), key));
        }
    }

    @Override
    public void releaseProbe(ProbeSlot slot, CircuitKey key, boolean success, Instant now) {
        // 结果经 recordResult 计入；此处回收在途名额由 recordResult 调用方保证顺序，
        // 简化：由 recordResult 的 HALF_OPEN 分支统计成败，名额在此回收
        InternalState state = states.get(key);
        if (state != null) {
            synchronized (state) {
                if (state.probeInflight > 0) {
                    state.probeInflight--;
                }
            }
        }
    }

    @Override
    public Optional<CircuitSnapshot> applyManualCommand(CircuitKey key, CircuitPolicy policy,
                                                        ManualCommand command, Instant now) {
        InternalState state = internal(key, policy, now);
        synchronized (state) {
            lazyTransition(state, policy, now);
            if (state.stateVersion != command.expectedStateVersion()) {
                return Optional.empty(); // CAS 失败：调用方返回 CIRCUIT_STATE_CONFLICT
            }
            switch (command.action()) {
                case MANUAL_OPEN -> transition(state, policy, CircuitSnapshot.STATE_OPEN,
                        "MANUAL_OPEN", command.commandId(), command.reason(), now);
                case MANUAL_RECOVER -> {
                    if (!CircuitSnapshot.STATE_CLOSED.equals(state.state)) {
                        transition(state, policy, CircuitSnapshot.STATE_CLOSED, "MANUAL_RECOVER",
                                command.commandId(), command.reason(), now);
                    }
                }
                default -> {
                    return Optional.empty();
                }
            }
            state.lastAppliedCommandId = command.commandId();
            return Optional.of(state.snapshot(policy, now));
        }
    }

    @Override
    public List<CircuitSnapshot> all() {
        List<CircuitSnapshot> snapshots = new ArrayList<>();
        Instant now = Instant.now();
        states.forEach((key, state) -> snapshots.add(state.snapshot(readPolicyOf(key), now)));
        return List.copyOf(snapshots);
    }

    /** 惰性迁移：OPEN 到期 → HALF_OPEN（重置探测计数，保留窗口）。 */
    private void lazyTransition(InternalState state, CircuitPolicy policy, Instant now) {
        if (CircuitSnapshot.STATE_OPEN.equals(state.state)
                && state.nextProbeAt != null && !now.isBefore(state.nextProbeAt)) {
            transition(state, policy, CircuitSnapshot.STATE_HALF_OPEN, "OPEN_EXPIRED",
                    null, null, now);
            state.probeSuccessCount = 0;
            state.probeInflight = 0;
        }
    }

    private void transition(InternalState state, CircuitPolicy policy, String toState,
                            String triggerType, UUID commandId, String reason, Instant now) {
        String from = state.state;
        if (from.equals(toState)) {
            return;
        }
        state.state = toState;
        state.stateVersion++;
        if (CircuitSnapshot.STATE_OPEN.equals(toState)) {
            state.openedAt = now;
            state.nextProbeAt = now.plusSeconds(policy.openSeconds());
            state.openSource = "MANUAL_OPEN".equals(triggerType) ? "MANUAL" : "AUTO";
            state.lastReason = reason;
        } else if (CircuitSnapshot.STATE_CLOSED.equals(toState)) {
            state.openedAt = null;
            state.nextProbeAt = null;
            state.requestCount = 0;
            state.failureCount = 0;
            state.probeSuccessCount = 0;
            state.probeInflight = 0;
            state.windowStartedAt = now;
        }
        publishEvent(new CircuitEventPayload(
                state.circuitId + ":" + state.stateVersion,
                state.key, from, toState, triggerType, commandId, null, reason, now));
    }

    private void publishEvent(CircuitEventPayload payload) {
        synchronized (eventLock) {
            for (EventListener listener : listeners) {
                listener.onEvent(payload);
            }
        }
    }

    private InternalState internal(CircuitKey key, CircuitPolicy policy, Instant now) {
        return states.computeIfAbsent(key, missing -> {
            InternalState created = new InternalState();
            created.circuitId = UUID.randomUUID();
            created.key = missing;
            created.state = CircuitSnapshot.STATE_CLOSED;
            created.stateVersion = 1;
            created.windowStartedAt = now;
            return created;
        });
    }

    /** 事件与快照读取使用最近注册策略（进程内实现的简化口径，快照含策略于 SQL）。 */
    private final Map<CircuitKey, CircuitPolicy> lastPolicies = new ConcurrentHashMap<>();

    private CircuitPolicy readPolicyOf(CircuitKey key) {
        return lastPolicies.computeIfAbsent(key, missing -> new CircuitPolicy(
                null, 0, 60, 20, 0.5, 30, 3, 2));
    }

    /** 内部可变状态。 */
    private static final class InternalState {
        private UUID circuitId;
        private CircuitKey key;
        private String state;
        private long stateVersion;
        private Instant windowStartedAt;
        private int requestCount;
        private int failureCount;
        private int probeInflight;
        private int probeSuccessCount;
        private Instant openedAt;
        private Instant nextProbeAt;
        private String openSource;
        private String lastReason;
        private UUID lastAppliedCommandId;

        void ensureWindow(CircuitPolicy policy, Instant now) {
            if (windowStartedAt == null
                    || windowStartedAt.plusSeconds(policy.windowSeconds()).isBefore(now)) {
                windowStartedAt = now;
                requestCount = 0;
                failureCount = 0;
            }
        }

        CircuitSnapshot snapshot(CircuitPolicy policy, Instant now) {
            return new CircuitSnapshot(circuitId, key, state, stateVersion, windowStartedAt,
                    requestCount, failureCount, probeInflight, probeSuccessCount,
                    openedAt, nextProbeAt, openSource, lastReason, lastAppliedCommandId);
        }
    }
}
