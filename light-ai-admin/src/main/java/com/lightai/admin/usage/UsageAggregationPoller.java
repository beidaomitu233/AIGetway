package com.lightai.admin.usage;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Usage 聚合事件轮询（BE-033）：单线程定时消费 Outbox 事件，
 * 每轮最多处理 50 条；异常只记录告警并等待下一轮，不中断调度。
 * 轮询间隔 5 秒，保证聚合延迟不超过两个 dashboard_refresh_seconds 的量级；
 * 容器关闭时停止接收新任务，不等待在途轮次（事件租约保证可接管）。
 */
public class UsageAggregationPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UsageAggregationPoller.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final int BATCH_SIZE = 50;

    private final UsageAggregator aggregator;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public UsageAggregationPoller(UsageAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "light-ai-usage-aggregation");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::drain, POLL_INTERVAL.toSeconds(),
                POLL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
        running = true;
    }

    private void drain() {
        try {
            aggregator.processPending(BATCH_SIZE);
        } catch (Exception e) {
            log.warn("Usage聚合轮询异常，等待下一轮重试: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
