package com.lightai.runtime.circuit;

import java.time.Instant;
import java.util.UUID;

/**
 * 熔断策略快照（来自固定活动快照的 ReliabilityPolicy 熔断段）。
 * 失败率以小数表示（0.5 = 50%）。
 */
public record CircuitPolicy(UUID policyId, long snapshotNo, int windowSeconds, int minRequests,
                            double failureRate, int openSeconds, int halfOpenProbes,
                            int halfOpenSuccesses) {
}
