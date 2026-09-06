package com.lightai.admin.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.storage.trace.ObservationRows.AttemptRow;
import com.lightai.storage.trace.ObservationRows.TraceRow;
import com.lightai.storage.trace.JdbcUsageAggregateRepository.Contribution;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * BE-033 聚合贡献测试：请求/执行贡献分离、路径归因（RV-018）、settled 过滤、
 * 实际/估算互斥拆分、HOUR/DAY 双粒度、时区桶、dimension_key 规范化。
 */
class ContributionCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final OffsetDateTime STARTED = OffsetDateTime.parse("2026-09-06T01:30:00+08:00");

    private TraceRow trace(UUID finalAttemptId, String aliasId) {
        return new TraceRow(
                UUID.randomUUID(), "trace-1", "app-a", "proj", "tenant", Map.of(),
                "EMBEDDED", "APPLICATION",
                aliasId == null ? null : UUID.fromString(aliasId), "alias-x",
                7L, true, true, "SUCCEEDED", STARTED,
                STARTED.plusMinutes(10), STARTED.plusMinutes(9), 9000, 800,
                1000L, 2, 1, 0, 1,
                finalAttemptId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(),
                "provider-b", "model-b", "cred-b",
                100, 200, 300, 100, 200, 300, "MIXED",
                new BigDecimal("0.001"), new BigDecimal("0.002"), new BigDecimal("0.003"),
                "USD", "stop", null, null, null, null, false,
                Map.of(), null, null, STARTED.plusMinutes(9));
    }

    private AttemptRow attempt(int sequence, String type, String status, String provider,
                               String usageSource, long input, long output, boolean settled) {
        return new AttemptRow(
                UUID.randomUUID(), "trace-1", sequence, type, UUID.randomUUID(),
                UUID.nameUUIDFromBytes(provider.getBytes()), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(),
                provider, "model-" + provider, "model-id", "cred-" + provider,
                status, STARTED.plusSeconds(sequence * 10L), null, null, null,
                STARTED.plusSeconds(sequence * 10L + 5), 100, null, 400, 500,
                "api.provider.com", 200, "req-" + sequence, false, "stop",
                null, null, null, null, false, null,
                Map.of(), input, output, input + output, usageSource,
                new BigDecimal("0.001"), new BigDecimal("0.002"), 1000000, "USD",
                new BigDecimal("0.00100000"), new BigDecimal("0.00200000"),
                new BigDecimal("0.00300000"),
                settled ? STARTED.plusSeconds(sequence * 10L + 6) : null);
    }

    @Test
    void fallbackTraceKeepsFailedPathTokensWithoutRequestCount() {
        AttemptRow failed = attempt(1, "INITIAL", "FAILED", "provider-a", "ACTUAL", 10, 20, true);
        AttemptRow success = attempt(2, "FALLBACK", "SUCCEEDED", "provider-b", "ACTUAL", 100, 200, true);
        TraceRow trace = trace(success.id(), null);

        List<Contribution> hourContributions = ContributionCalculator.compute(trace,
                List.of(failed, success), ZONE).stream()
                .filter(c -> "HOUR".equals(c.granularity()))
                .toList();

        // 请求贡献：request_count=1，路径为 final_attempt（provider-b），无 Token
        Contribution request = hourContributions.stream()
                .filter(c -> c.requestCount() == 1).findFirst().orElseThrow();
        assertThat(request.attemptCount()).isZero();
        assertThat(request.totalTokens()).isZero();
        assertThat(request.successCount()).isEqualTo(1);
        assertThat(request.streamCount()).isEqualTo(1);
        assertThat(request.traceStatus()).isEqualTo("SUCCEEDED");
        assertThat(request.dimensionNames().get("provider")).isEqualTo("provider-b");

        // 执行贡献：失败路径保留 Token 与费用且 request_count=0（RV-018）
        Contribution failedExecution = hourContributions.stream()
                .filter(c -> c.requestCount() == 0
                        && "provider-a".equals(c.dimensionNames().get("provider")))
                .findFirst().orElseThrow();
        assertThat(failedExecution.attemptCount()).isEqualTo(1);
        assertThat(failedExecution.initialCount()).isEqualTo(1);
        assertThat(failedExecution.totalTokens()).isEqualTo(30);
        assertThat(failedExecution.totalCost()).isEqualByComparingTo(new BigDecimal("0.00300000"));

        Contribution successExecution = hourContributions.stream()
                .filter(c -> c.requestCount() == 0
                        && "provider-b".equals(c.dimensionNames().get("provider")))
                .findFirst().orElseThrow();
        assertThat(successExecution.fallbackCount()).isEqualTo(1);
        assertThat(successExecution.totalTokens()).isEqualTo(300);
    }

    @Test
    void unsettledAttemptsAreExcludedFromExecutionContributions() {
        AttemptRow unsettled = attempt(1, "INITIAL", "FAILED", "provider-a", "ACTUAL", 10, 20, false);
        AttemptRow settled = attempt(2, "RETRY", "SUCCEEDED", "provider-b", "ESTIMATED", 100, 200, true);
        TraceRow trace = trace(settled.id(), null);

        List<Contribution> contributions = ContributionCalculator.compute(trace,
                List.of(unsettled, settled), ZONE);
        assertThat(contributions.stream().filter(c -> c.requestCount() == 0)).hasSize(2);

        // 估算 Attempt 进入 estimated_*，不进入 actual_*
        Contribution estimated = contributions.stream()
                .filter(c -> c.requestCount() == 0
                        && "provider-b".equals(c.dimensionNames().get("provider")))
                .findFirst().orElseThrow();
        assertThat(estimated.estimatedInputTokens()).isEqualTo(100);
        assertThat(estimated.estimatedOutputTokens()).isEqualTo(200);
        assertThat(estimated.actualInputTokens()).isZero();
    }

    @Test
    void producesBothGranularitiesWithTimezoneBuckets() {
        AttemptRow success = attempt(1, "INITIAL", "SUCCEEDED", "provider-b", "ACTUAL", 1, 1, true);
        TraceRow trace = trace(success.id(), null);

        List<Contribution> all = ContributionCalculator.compute(trace, List.of(success), ZONE);
        assertThat(all.stream().filter(c -> "HOUR".equals(c.granularity()))).hasSize(2);
        assertThat(all.stream().filter(c -> "DAY".equals(c.granularity()))).hasSize(2);

        Contribution hour = all.stream().filter(c -> "HOUR".equals(c.granularity()))
                .filter(c -> c.requestCount() == 1).findFirst().orElseThrow();
        // 2026-09-06T01:30+08:00 → HOUR 桶起点 01:00+08:00 = 17:00Z 前一天
        assertThat(hour.bucketStart().toInstant().toString())
                .isEqualTo("2026-09-05T17:00:00Z");
        assertThat(hour.bucketEnd().toInstant().toString())
                .isEqualTo("2026-09-05T18:00:00Z");

        Contribution day = all.stream().filter(c -> "DAY".equals(c.granularity()))
                .filter(c -> c.requestCount() == 1).findFirst().orElseThrow();
        // DAY 桶按配置时区自然日：2026-09-06 00:00+08:00 = 2026-09-05T16:00Z
        assertThat(day.bucketStart().toInstant().toString())
                .isEqualTo("2026-09-05T16:00:00Z");
        assertThat(day.bucketEnd().toInstant().toString())
                .isEqualTo("2026-09-06T16:00:00Z");
    }

    @Test
    void dimensionKeyIsStableOrderedAndDistinguishesNullFromEmpty() {
        Map<String, String> base = new java.util.HashMap<>();
        base.put("application", "app-a");
        base.put("project", "");
        base.put("tenant", null);
        base.put("alias_id", "11111111-1111-1111-1111-111111111111");
        base.put("provider_id", null);
        base.put("provider_model_id", null);
        base.put("credential_pool_id", null);
        base.put("credential_id", null);
        base.put("trace_status", "SUCCEEDED");
        base.put("error_code", null);
        base.put("usage_source", "ACTUAL");
        base.put("requested_stream", "true");

        String key = ContributionCalculator.dimensionKey(base);
        assertThat(key).hasSize(64).matches("[0-9a-f]{64}");

        // 键序无关：乱序输入得到相同 key
        Map<String, String> reordered = new java.util.HashMap<>(base);
        assertThat(ContributionCalculator.dimensionKey(reordered)).isEqualTo(key);

        // null 与空字符串不混用：project 空串 vs null 产生不同 key
        Map<String, String> nullProject = new java.util.HashMap<>(base);
        nullProject.put("project", null);
        assertThat(ContributionCalculator.dimensionKey(nullProject))
                .isNotEqualTo(key);
    }

    @Test
    void requestContributionCarriesLatencyHistogramOnlyForFirstTokenStreams() {
        AttemptRow success = attempt(1, "INITIAL", "SUCCEEDED", "provider-b", "ACTUAL", 1, 1, true);
        TraceRow trace = trace(success.id(), null);
        Contribution request = ContributionCalculator.compute(trace, List.of(success), ZONE)
                .stream().filter(c -> "HOUR".equals(c.granularity()) && c.requestCount() == 1)
                .findFirst().orElseThrow();
        assertThat(request.latencyHistogram()).containsEntry("9000", 1L);
        assertThat(request.firstTokenHistogram()).containsEntry("800", 1L);
        assertThat(request.totalMsSum()).isEqualTo(9000);
        assertThat(request.firstTokenMsSum()).isEqualTo(800);
    }

    @Test
    void noAttemptTraceHasEmptyPathAndTraceCurrency() {
        TraceRow trace = trace(null, null);
        trace = new TraceRow(
                trace.id(), trace.traceId(), trace.application(), trace.project(), trace.tenant(),
                trace.tags(), trace.sourceMode(), trace.invocationSource(), null, null,
                trace.configSnapshotNo(), trace.requestedStream(), trace.responseCommitted(),
                "FAILED", trace.startedAt(), trace.deadlineAt(), trace.endedAt(),
                trace.totalMs(), trace.firstTokenMs(), trace.queuedMs(), trace.attemptCount(),
                trace.retryCount(), trace.credentialFailoverCount(), trace.fallbackCount(),
                null, null, null, null, null, null, null, null,
                0, 0, 0, 0, 0, 0, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "USD",
                null, "MODEL_CAPABILITY_NOT_SUPPORTED", null, null, null, false,
                trace.requestSummary(), null, null, trace.updatedAt());

        List<Contribution> contributions = new ArrayList<>(ContributionCalculator.compute(
                trace, List.of(), ZONE));
        Contribution request = contributions.stream()
                .filter(c -> "HOUR".equals(c.granularity())).findFirst().orElseThrow();
        assertThat(request.requestCount()).isEqualTo(1);
        assertThat(request.failureCount()).isEqualTo(1);
        assertThat(request.dimensionNames().get("provider")).isNull();
        assertThat(request.usageSource()).isNull();
        assertThat(request.currency()).isEqualTo("USD");
        assertThat(request.errorCode()).isEqualTo("MODEL_CAPABILITY_NOT_SUPPORTED");
    }
}
