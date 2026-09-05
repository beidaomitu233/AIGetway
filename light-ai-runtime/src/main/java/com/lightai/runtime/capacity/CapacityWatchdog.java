package com.lightai.runtime.capacity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 容量 Watchdog（BE-024）：失联 Attempt 先最终化孤儿调用，再释放异常预占，
 * 避免永久 RUNNING 与并发泄漏。部署侧按调度周期调用 sweep。
 */
public class CapacityWatchdog {

    /** 失联判定：运行租约到期仍未终态。 */
    public interface LeaseRegistry {
        /** 返回租约到期的预留（含 attempt_id），由 watchdog 最终化。 */
        List<UUID> expiredReservations(Instant now);
    }

    private final CapacityStore capacityStore;
    private final LeaseRegistry leaseRegistry;
    private final Consumer<UUID> orphanFinalizer;
    private final long leaseSeconds;

    public CapacityWatchdog(CapacityStore capacityStore, LeaseRegistry leaseRegistry,
                            Consumer<UUID> orphanFinalizer, long leaseSeconds) {
        this.capacityStore = capacityStore;
        this.leaseRegistry = leaseRegistry;
        this.orphanFinalizer = orphanFinalizer;
        this.leaseSeconds = leaseSeconds;
    }

    /** 单次清扫：先最终化孤儿 Attempt，再释放异常预留；重复调用幂等。 */
    public int sweep(Instant now) {
        int swept = 0;
        List<UUID> expired = leaseRegistry.expiredReservations(now.minusSeconds(leaseSeconds));
        for (UUID reservationId : expired) {
            // 先最终化孤儿调用（Trace/Attempt 终态由回调执行），再释放预占
            if (orphanFinalizer != null) {
                orphanFinalizer.accept(reservationId);
            }
            capacityStore.release(reservationId);
            swept++;
        }
        return swept;
    }
}
