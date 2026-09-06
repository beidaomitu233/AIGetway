package com.lightai.admin.cleanup;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetentionCleanupServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldCleanAuditAndSamplesEvenWhenAggregationPending() {
        FakeDeletionPort port = new FakeDeletionPort();
        port.pendingEvents = 5;
        port.expiredAudit = 12;
        port.expiredSamples = 3;
        port.expiredUsage = 7;

        RetentionCleanupService service = new RetentionCleanupService(
                port, clock,
                () -> OffsetDateTime.now(clock).minusDays(7),
                () -> OffsetDateTime.now(clock).minusDays(90),
                () -> OffsetDateTime.now(clock).minusDays(365),
                () -> OffsetDateTime.now(clock).minusDays(3));

        RetentionCleanupService.CleanupReport report = service.run();

        assertTrue(report.skippedPendingAggregation);
        assertEquals(0, report.deletedTraces);
        assertEquals(0, report.deletedTraceDetails);
        assertEquals(12, report.deletedAudit);
        assertEquals(3, report.deletedSamples);
        assertEquals(7, report.deletedUsage);
        assertNotNull(report.finishedAt);
    }

    @Test
    void shouldCleanAllWhenNoAggregationPending() {
        FakeDeletionPort port = new FakeDeletionPort();
        port.pendingEvents = 0;
        port.expiredTraceIds.addAll(List.of("t1", "t2", "t3"));
        port.expiredAudit = 5;
        port.expiredSamples = 2;
        port.expiredUsage = 10;

        RetentionCleanupService service = new RetentionCleanupService(
                port, clock,
                () -> OffsetDateTime.now(clock).minusDays(7),
                () -> OffsetDateTime.now(clock).minusDays(90),
                () -> OffsetDateTime.now(clock).minusDays(365),
                () -> OffsetDateTime.now(clock).minusDays(3));

        RetentionCleanupService.CleanupReport report = service.run();

        assertFalse(report.skippedPendingAggregation);
        assertEquals(3, report.deletedTraces);
        assertEquals(3, report.deletedTraceDetails);
        assertEquals(5, report.deletedAudit);
        assertEquals(2, report.deletedSamples);
        assertEquals(10, report.deletedUsage);
        assertEquals(1, report.batches);
    }

    private static class FakeDeletionPort implements RetentionCleanupService.DeletionPort {
        long pendingEvents = 0;
        List<String> expiredTraceIds = new ArrayList<>();
        long expiredAudit = 0;
        long expiredSamples = 0;
        long expiredUsage = 0;

        @Override
        public List<String> findExpiredTraceIds(OffsetDateTime cutoff, int limit) {
            List<String> batch = new ArrayList<>(expiredTraceIds);
            expiredTraceIds.clear();
            return batch;
        }

        @Override
        public int deleteTraceDetails(List<String> traceIds) {
            return traceIds.size();
        }

        @Override
        public int deleteTraces(List<String> traceIds) {
            return traceIds.size();
        }

        @Override
        public long deleteExpiredAudit(OffsetDateTime cutoff) {
            return expiredAudit;
        }

        @Override
        public long deleteExpiredSamples(OffsetDateTime cutoff) {
            return expiredSamples;
        }

        @Override
        public long deleteExpiredUsage(OffsetDateTime cutoff) {
            return expiredUsage;
        }

        @Override
        public long pendingAggregationEvents() {
            return pendingEvents;
        }
    }
}
