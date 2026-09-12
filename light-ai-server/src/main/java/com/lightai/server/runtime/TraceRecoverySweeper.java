package com.lightai.server.runtime;

import com.lightai.runtime.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 进程崩溃恢复（BE-225）：周期收敛超过 deadline 仍处于 RUNNING/QUEUED 的 Trace。
 * 预算与容量回收由既有 reclaimExpired 任务执行，本任务只补 Trace/Attempt 终态，
 * 幂等可重入，多实例并发安全（终态转换 CAS）。
 */
public final class TraceRecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(TraceRecoverySweeper.class);

    private final TraceStore traceStore;

    public TraceRecoverySweeper(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @Scheduled(fixedDelayString = "${light-ai.trace.recovery-interval-ms:30000}")
    public void sweep() {
        try {
            int converged = traceStore.finalizeExpired(java.time.Instant.now(), "TOTAL_TIMEOUT");
            if (converged > 0) {
                log.info("Trace 恢复清扫收敛 {} 条过期运行记录", converged);
            }
        } catch (RuntimeException e) {
            log.warn("Trace 恢复清扫失败 exception={}", e.getClass().getSimpleName());
        }
    }
}
