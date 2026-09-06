package com.lightai.runtime.export;

import com.lightai.spi.export.ExportedTrace;
import com.lightai.spi.export.TraceExportBatch;
import com.lightai.spi.export.TraceExportResult;
import com.lightai.spi.export.TraceExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TraceExportCoordinatorTest {

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setup() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private ExportedTrace dummyTrace(String id) {
        return new ExportedTrace(
                id, "alias-1", "OPENAI", "gpt-4o", "SUCCEEDED",
                150L, Instant.now(), Instant.now(),
                10L, 20L, 30L, BigDecimal.ZERO, "USD",
                null, null
        );
    }

    @Test
    void shouldExportBatchSuccessfully() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        TraceExporter exporter = new TraceExporter() {
            @Override
            public CompletionStage<TraceExportResult> export(TraceExportBatch batch) {
                latch.countDown();
                return CompletableFuture.completedFuture(TraceExportResult.success(batch.batchId()));
            }
        };

        TraceExportCoordinator coordinator = new TraceExportCoordinator(List.of(exporter), null, scheduler);
        boolean submitted = coordinator.submit(dummyTrace("trace-1"));

        assertThat(submitted).isTrue();
        boolean passed = latch.await(2, TimeUnit.SECONDS);
        assertThat(passed).isTrue();
        assertThat(coordinator.exporterSuccesses()).isEqualTo(1);
        assertThat(coordinator.exporterFailures()).isEqualTo(0);
        coordinator.close();
    }

    @Test
    void shouldRetryOnFailureAndEventuallySucceed() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        TraceExporter exporter = new TraceExporter() {
            @Override
            public CompletionStage<TraceExportResult> export(TraceExportBatch batch) {
                int count = attempts.incrementAndGet();
                latch.countDown();
                if (count < 3) {
                    return CompletableFuture.completedFuture(TraceExportResult.failure(batch.batchId(), "Transient error " + count));
                }
                return CompletableFuture.completedFuture(TraceExportResult.success(batch.batchId()));
            }
        };

        Duration[] fastRetries = new Duration[]{Duration.ofMillis(20), Duration.ofMillis(30), Duration.ofMillis(50)};
        TraceExportCoordinator coordinator = new TraceExportCoordinator(List.of(exporter), fastRetries, scheduler);

        coordinator.submit(dummyTrace("trace-retry"));

        boolean finished = latch.await(3, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(coordinator.exporterSuccesses()).isEqualTo(1);
        coordinator.close();
    }

    @Test
    void shouldRecordFailureWhenAllRetriesExhausted() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(4); // 1 initial + 3 retries

        TraceExporter exporter = new TraceExporter() {
            @Override
            public CompletionStage<TraceExportResult> export(TraceExportBatch batch) {
                attempts.incrementAndGet();
                latch.countDown();
                return CompletableFuture.completedFuture(TraceExportResult.failure(batch.batchId(), "Permanent failure"));
            }
        };

        Duration[] fastRetries = new Duration[]{Duration.ofMillis(20), Duration.ofMillis(30), Duration.ofMillis(40)};
        TraceExportCoordinator coordinator = new TraceExportCoordinator(List.of(exporter), fastRetries, scheduler);

        coordinator.submit(dummyTrace("trace-fail"));

        boolean finished = latch.await(3, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(coordinator.exporterFailures()).isEqualTo(1);
        assertThat(coordinator.exporterSuccesses()).isEqualTo(0);
        coordinator.close();
    }

    @Test
    void shouldIsolateSynchronousExceptionFromExporter() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        TraceExporter exporter = new TraceExporter() {
            @Override
            public CompletionStage<TraceExportResult> export(TraceExportBatch batch) {
                latch.countDown();
                throw new RuntimeException("Unexpected synchronous crash");
            }
        };

        Duration[] fastRetries = new Duration[]{Duration.ofMillis(10)};
        TraceExportCoordinator coordinator = new TraceExportCoordinator(List.of(exporter), fastRetries, scheduler);

        boolean submitted = coordinator.submit(dummyTrace("trace-crash"));
        assertThat(submitted).isTrue();

        boolean ran = latch.await(2, TimeUnit.SECONDS);
        assertThat(ran).isTrue();
        coordinator.close();
    }
}