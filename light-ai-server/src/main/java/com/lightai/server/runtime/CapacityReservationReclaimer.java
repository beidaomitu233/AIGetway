package com.lightai.server.runtime;

import com.lightai.runtime.capacity.CapacityStore;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 回收实例崩溃或超时后未正常结算的共享容量预占。 */
@Component
public final class CapacityReservationReclaimer {

    private static final Logger log = LoggerFactory.getLogger(CapacityReservationReclaimer.class);
    private final CapacityStore capacityStore;

    public CapacityReservationReclaimer(CapacityStore capacityStore) {
        this.capacityStore = capacityStore;
    }

    @Scheduled(fixedDelayString = "${light-ai.redis.capacity-reclaim-interval-ms:30000}")
    public void reclaim() {
        try {
            int reclaimed = capacityStore.reclaimExpired(Instant.now());
            if (reclaimed > 0) {
                log.info("已回收 {} 个超时容量预占", reclaimed);
            }
        } catch (RuntimeException e) {
            log.warn("容量预占回收暂不可用");
        }
    }
}
