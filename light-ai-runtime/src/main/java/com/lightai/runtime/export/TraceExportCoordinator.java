package com.lightai.runtime.export;

import com.lightai.spi.export.ExportedTrace;
import com.lightai.spi.export.TraceExportBatch;
import com.lightai.spi.export.TraceExportResult;
import com.lightai.spi.export.TraceExporter;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace 导出协调器（BE-054，4.6.3.6）：
 * 1. 本地有界队列：上限 <= 10000；
 * 2. 异步导出隔离：导出失败绝不影响业务响应成功；
 * 3. 幂等重试：失败按 1s、5s、30s 执行 3 次重试，保持相同 batchId；
 * 4. 最终失败：仅递增 exporter_failure 指标并输出安全日志，无消息正文与密钥。
 */
public class TraceExportCoordinator {

    public static final int MAX_QUEUE_CAPACITY = 10000;

    private final List<TraceExporter> exporters;
    private final BlockingQueue<TraceExportBatch> queue = new LinkedBlockingQueue<>(MAX_QUEUE_CAPACITY);
    private final ScheduledExecutorService scheduler;
    private final boolean ownExecutor;
    private final Duration[] retryDelays;

    private final AtomicLong queueFullDrops = new AtomicLong(0);
    private final AtomicLong exporterFailures = new AtomicLong(0);
    private final AtomicLong exporterSuccesses = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TraceExportCoordinator(List<TraceExporter> exporters) {
        this(exporters, new Duration[]{Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)}, null);
    }

    public TraceExportCoordinator(List<TraceExporter> exporters, Duration[] retryDelays, ScheduledExecutorService scheduler) {
        this.exporters = exporters != null ? List.copyOf(exporters) : List.of();
        this.retryDelays = retryDelays != null ? retryDelays : new Duration[]{Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)};
        if (scheduler != null) {
            this.scheduler = scheduler;
            this.ownExecutor = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "light-ai-trace-exporter");
                t.setDaemon(true);
                return t;
            });
            this.ownExecutor = true;
        }
    }

    public boolean hasExporters() {
        return !exporters.isEmpty();
    }

    /**
     * 提交 Trace 进行异步导出（BE-054）。
     */
    public boolean submit(ExportedTrace trace) {
        if (trace == null || exporters.isEmpty() || closed.get()) {
            return false;
        }
        TraceExportBatch batch = new TraceExportBatch(UUID.randomUUID().toString(), List.of(trace));
        return submitBatch(batch);
    }

    /**
     * 提交批次导出。
     */
    public boolean submitBatch(TraceExportBatch batch) {
        if (batch == null || exporters.isEmpty() || closed.get()) {
            return false;
        }

        boolean queued = queue.offer(batch);
        if (!queued) {
            queueFullDrops.incrementAndGet();
            System.err.println("[WARN] TraceExporter 队列已满 (10000)，丢弃导出批次: " + batch.batchId());
            return false;
        }

        triggerDispatch(batch, 0);
        return true;
    }

    private void triggerDispatch(TraceExportBatch batch, int attempt) {
        if (closed.get()) {
            return;
        }

        for (TraceExporter exporter : exporters) {
            try {
                exporter.export(batch).whenComplete((result, error) -> {
                    if (error == null && result != null && result.success()) {
                        exporterSuccesses.incrementAndGet();
                        queue.remove(batch);
                    } else {
                        handleFailure(exporter, batch, attempt, error != null ? error.getMessage() : (result != null ? result.errorMessage() : "unknown"));
                    }
                });
            } catch (Throwable t) {
                handleFailure(exporter, batch, attempt, t.getMessage());
            }
        }
    }

    private void handleFailure(TraceExporter exporter, TraceExportBatch batch, int attempt, String errorMessage) {
        if (attempt < retryDelays.length) {
            Duration delay = retryDelays[attempt];
            scheduler.schedule(() -> triggerDispatch(batch, attempt + 1), delay.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            // 3 次重试仍失败：记录指标并记录安全日志（无请求正文与密钥）
            exporterFailures.incrementAndGet();
            queue.remove(batch);
            System.err.println("[WARN] TraceExporter 批次 " + batch.batchId() + " 经 " + (attempt) + " 次重试最终失败: " + errorMessage);
        }
    }

    public long queueFullDrops() {
        return queueFullDrops.get();
    }

    public long exporterFailures() {
        return exporterFailures.get();
    }

    public long exporterSuccesses() {
        return exporterSuccesses.get();
    }

    public int queueSize() {
        return queue.size();
    }

    public void close() {
        closed.set(true);
        if (ownExecutor) {
            scheduler.shutdownNow();
        }
    }
}