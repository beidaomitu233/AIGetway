package com.lightai.runtime.circuit;

import java.time.Instant;
import java.util.UUID;

/**
 * 熔断状态快照（DATABASE_PLAN circuit_state 投影）。
 * state_version 为共享 CAS 版本；窗口计数含探测。
 */
public record CircuitSnapshot(
        UUID circuitId,
        CircuitKey key,
        String state,
        long stateVersion,
        Instant windowStartedAt,
        int requestCount,
        int failureCount,
        int probeInflight,
        int probeSuccessCount,
        Instant openedAt,
        Instant nextProbeAt,
        String openSource,
        String lastReason,
        UUID lastAppliedCommandId) {

    public static final String STATE_CLOSED = "CLOSED";
    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_HALF_OPEN = "HALF_OPEN";
}
