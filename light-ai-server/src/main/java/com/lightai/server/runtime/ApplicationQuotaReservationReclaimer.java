package com.lightai.server.runtime;

import com.lightai.runtime.ports.ApplicationQuotaPort;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 回收实例异常退出或进程中断后遗留的应用 Token/金额预占。 */
@Component
public final class ApplicationQuotaReservationReclaimer {

    private static final Logger log = LoggerFactory.getLogger(ApplicationQuotaReservationReclaimer.class);
    private final ApplicationQuotaPort quotaPort;

    public ApplicationQuotaReservationReclaimer(ApplicationQuotaPort quotaPort) {
        this.quotaPort = quotaPort;
    }

    @Scheduled(fixedDelayString = "${light-ai.quota.reclaim-interval-ms:30000}")
    public void reclaim() {
        try {
            int reclaimed = quotaPort.reclaimExpired(Instant.now());
            if (reclaimed > 0) log.info("已回收 {} 个超时应用额度预占", reclaimed);
        } catch (RuntimeException failure) {
            log.warn("应用额度预占回收暂不可用");
        }
    }
}
