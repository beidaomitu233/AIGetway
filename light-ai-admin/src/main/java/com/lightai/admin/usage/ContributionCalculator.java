package com.lightai.admin.usage;

import com.lightai.storage.trace.JdbcUsageAggregateRepository.Contribution;
import com.lightai.storage.trace.ObservationRows.AttemptRow;
import com.lightai.storage.trace.ObservationRows.TraceRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 聚合贡献计算（BE-033；BACKEND_PLAN 4.4.3.6）。
 * 每个 Trace 生成一条请求贡献、每个已结算 Attempt 一条执行贡献：
 * 请求贡献按 Trace 取值且路径维度用 final_attempt 路径，Attempt/Token/费用全部为 0；
 * 执行贡献 request_count 等 Trace 指标为 0，按实际 Attempt 取值。
 * 两类贡献按相同 dimension_key 合并，因此任意查询层级不重复统计 request_count，
 * 同时保留前序失败路径的 Token 与费用（RV-018：Provider A request_count=0 且保留费用）。
 * Token 与费用只统计已结算 Attempt；实际与估算按 Attempt.usage_source 互斥拆分。
 * dimension_key 为固定维度顺序的规范化 SHA256（char(64)），currency 另列纳入唯一键。
 */
public final class ContributionCalculator {

    public static final String GRANULARITY_HOUR = "HOUR";
    public static final String GRANULARITY_DAY = "DAY";

    /** dimension_key 输入的固定维度顺序（DATABASE_PLAN 4.2）。 */
    private static final String[] DIMENSION_ORDER = {
            "application", "project", "tenant", "alias_id", "provider_id", "provider_model_id",
            "credential_pool_id", "credential_id", "trace_status", "error_code", "usage_source",
            "requested_stream"};

    private ContributionCalculator() {
    }

    /** 返回 HOUR 与 DAY 两种粒度的全部贡献；调用方在同一事务内写入。 */
    public static List<Contribution> compute(TraceRow trace, List<AttemptRow> attempts,
                                             ZoneId zone) {
        List<Contribution> contributions = new ArrayList<>();
        contributions.addAll(singleGranularity(trace, attempts, zone, GRANULARITY_HOUR));
        contributions.addAll(singleGranularity(trace, attempts, zone, GRANULARITY_DAY));
        return List.copyOf(contributions);
    }

    private static List<Contribution> singleGranularity(TraceRow trace,
                                                        List<AttemptRow> attempts,
                                                        ZoneId zone, String granularity) {
        boolean hour = GRANULARITY_HOUR.equals(granularity);
        OffsetDateTime bucketStart = bucketStart(trace.startedAt(), zone, hour);
        OffsetDateTime bucketEnd = bucketEndFromStart(bucketStart, zone, granularity);

        List<Contribution> contributions = new ArrayList<>();
        contributions.add(requestContribution(trace, attempts, granularity,
                bucketStart, bucketEnd));
        for (AttemptRow attempt : attempts) {
            if (attempt.settledAt() == null) {
                continue;
            }
            contributions.add(executionContribution(trace, attempt, granularity,
                    bucketStart, bucketEnd));
        }
        return contributions;
    }

    private static Contribution requestContribution(TraceRow trace, List<AttemptRow> attempts,
                                                    String granularity,
                                                    OffsetDateTime bucketStart,
                                                    OffsetDateTime bucketEnd) {
        AttemptRow finalAttempt = finalAttempt(trace, attempts);
        String status = trace.status();
        boolean streaming = trace.requestedStream();

        return build(trace, null, finalAttempt, null, granularity, bucketStart, bucketEnd,
                1,
                "SUCCEEDED".equals(status) ? 1 : 0,
                "FAILED".equals(status) ? 1 : 0,
                "CANCELLED".equals(status) ? 1 : 0,
                "STREAM_INTERRUPTED".equals(status) ? 1 : 0,
                "QUEUED".equals(status) ? 1 : 0,
                streaming ? 1 : 0,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                trace.totalMs() == null ? 0 : trace.totalMs(),
                trace.totalMs() == null ? 0 : 1,
                streaming && trace.firstTokenMs() != null ? trace.firstTokenMs() : 0,
                streaming && trace.firstTokenMs() != null ? 1 : 0,
                trace.queuedMs(),
                histogramOf(trace.totalMs()),
                histogramOf(streaming ? trace.firstTokenMs() : null));
    }

    private static Contribution executionContribution(TraceRow trace, AttemptRow attempt,
                                                      String granularity,
                                                      OffsetDateTime bucketStart,
                                                      OffsetDateTime bucketEnd) {
        boolean actual = "ACTUAL".equals(attempt.usageSource());
        long inputTokens = attempt.inputTokens();
        long outputTokens = attempt.outputTokens();

        return build(trace, attempt, attempt, attempt, granularity, bucketStart, bucketEnd,
                0, 0, 0, 0, 0, 0, 0,
                1,
                "INITIAL".equals(attempt.attemptType()) ? 1 : 0,
                "RETRY".equals(attempt.attemptType()) ? 1 : 0,
                "CREDENTIAL_FAILOVER".equals(attempt.attemptType()) ? 1 : 0,
                "FALLBACK".equals(attempt.attemptType()) ? 1 : 0,
                "HALF_OPEN_PROBE".equals(attempt.attemptType()) ? 1 : 0,
                inputTokens, outputTokens, attempt.totalTokens(),
                actual ? inputTokens : 0, actual ? outputTokens : 0,
                actual ? 0 : inputTokens, actual ? 0 : outputTokens,
                attempt.inputCost(), attempt.outputCost(), attempt.totalCost(),
                0, 0, 0, 0, 0, Map.of(), Map.of());
    }

    /**
     * @param errorSource 请求贡献为 null（用 Trace.error_code），执行贡献为该 Attempt
     *                    （FE-035：ERROR_CODE 的 request_count 用 Trace 最终错误码，
     *                    Attempt 与费用用各 Attempt.error_code）
     */
    private static Contribution build(TraceRow trace, AttemptRow pathAttempt,
                                      AttemptRow usageSourceAttempt, AttemptRow errorCodeSource,
                                      String granularity, OffsetDateTime bucketStart,
                                      OffsetDateTime bucketEnd,
                                      long requestCount, long successCount, long failureCount,
                                      long cancelledCount, long streamInterruptedCount,
                                      long queuedCount, long streamCount,
                                      long attemptCount, long initialCount, long retryCount,
                                      long credentialFailoverCount, long fallbackCount,
                                      long halfOpenProbeCount,
                                      long inputTokens, long outputTokens, long totalTokens,
                                      long actualInputTokens, long actualOutputTokens,
                                      long estimatedInputTokens, long estimatedOutputTokens,
                                      BigDecimal inputCost, BigDecimal outputCost,
                                      BigDecimal totalCost,
                                      long totalMsSum, long totalMsCount,
                                      long firstTokenMsSum, long firstTokenMsCount,
                                      long queuedMsSum, Map<String, Long> latencyHistogram,
                                      Map<String, Long> firstTokenHistogram) {

        String usageSource = usageSourceAttempt == null ? null : usageSourceAttempt.usageSource();
        String errorCode = errorCodeSource == null ? trace.errorCode() : errorCodeSource.errorCode();
        String providerId = pathAttempt == null ? null : pathAttempt.providerId().toString();
        String providerModelId = pathAttempt == null ? null
                : pathAttempt.providerModelId().toString();
        String credentialPoolId = pathAttempt == null ? null
                : pathAttempt.credentialPoolId().toString();
        String credentialId = pathAttempt == null ? null : pathAttempt.credentialId().toString();
        boolean streaming = trace.requestedStream();

        Map<String, String> dimensionValues = new LinkedHashMap<>();
        dimensionValues.put("application", trace.application());
        dimensionValues.put("project", trace.project());
        dimensionValues.put("tenant", trace.tenant());
        dimensionValues.put("alias_id", trace.aliasId() == null ? null : trace.aliasId().toString());
        dimensionValues.put("provider_id", providerId);
        dimensionValues.put("provider_model_id", providerModelId);
        dimensionValues.put("credential_pool_id", credentialPoolId);
        dimensionValues.put("credential_id", credentialId);
        dimensionValues.put("trace_status", trace.status());
        dimensionValues.put("error_code", errorCode);
        dimensionValues.put("usage_source", usageSource);
        dimensionValues.put("requested_stream", String.valueOf(streaming));
        String dimensionKey = dimensionKey(dimensionValues);

        Map<String, String> dimensionNames = new HashMap<>();
        dimensionNames.put("alias", trace.alias());
        dimensionNames.put("provider", pathAttempt == null ? trace.finalProviderName()
                : pathAttempt.providerNameSnapshot());
        dimensionNames.put("provider_model", pathAttempt == null ? trace.finalProviderModelName()
                : pathAttempt.providerModelNameSnapshot());
        dimensionNames.put("credential_pool", null);
        dimensionNames.put("credential",
                pathAttempt == null ? null : pathAttempt.credentialNameSnapshot());

        return new Contribution(granularity, bucketStart, bucketEnd, dimensionKey,
                trace.application(), trace.project(), trace.tenant(), trace.aliasId(),
                pathAttempt == null ? null : pathAttempt.providerId(),
                pathAttempt == null ? null : pathAttempt.providerModelId(),
                pathAttempt == null ? null : pathAttempt.credentialPoolId(),
                pathAttempt == null ? null : pathAttempt.credentialId(),
                trace.status(), errorCode, usageSource, streaming, trace.currency(),
                dimensionNames,
                requestCount, successCount, failureCount, cancelledCount,
                streamInterruptedCount, queuedCount, streamCount,
                attemptCount, initialCount, retryCount, credentialFailoverCount,
                fallbackCount, halfOpenProbeCount,
                inputTokens, outputTokens, totalTokens,
                actualInputTokens, actualOutputTokens, estimatedInputTokens, estimatedOutputTokens,
                nvl(inputCost), nvl(outputCost), nvl(totalCost),
                totalMsSum, totalMsCount, firstTokenMsSum, firstTokenMsCount, queuedMsSum,
                latencyHistogram, firstTokenHistogram);
    }

    /** 请求贡献路径维度使用 final_attempt_id 对应 Attempt；未创建 Attempt 时路径为空。 */
    static AttemptRow finalAttempt(TraceRow trace, List<AttemptRow> attempts) {
        if (trace.finalAttemptId() == null) {
            return null;
        }
        for (AttemptRow attempt : attempts) {
            if (trace.finalAttemptId().equals(attempt.id())) {
                return attempt;
            }
        }
        return null;
    }

    /** 规范化维度 SHA256：null 用 JSON null 不与空字符串混用；固定顺序。 */
    public static String dimensionKey(Map<String, String> dimensionValues) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < DIMENSION_ORDER.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            String key = DIMENSION_ORDER[i];
            String value = dimensionValues.get(key);
            json.append('"').append(key).append("\":");
            json.append(value == null ? "null"
                    : "\"" + escapeJson(value) + "\"");
        }
        json.append('}');
        return sha256Hex(json.toString());
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    public static String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** HOUR 按配置时区自然小时截断；DAY 按配置时区自然日（本地日历，DST 安全）。 */
    public static OffsetDateTime bucketStart(OffsetDateTime instant, ZoneId zone, boolean hour) {
        ZonedDateTime local = instant.atZoneSameInstant(zone);
        if (hour) {
            return local.truncatedTo(ChronoUnit.HOURS).toOffsetDateTime();
        }
        LocalDate date = local.toLocalDate();
        return date.atStartOfDay(zone).toOffsetDateTime();
    }

    public static OffsetDateTime bucketEnd(OffsetDateTime instant, ZoneId zone, boolean hour) {
        ZonedDateTime local = instant.atZoneSameInstant(zone);
        if (hour) {
            return local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toOffsetDateTime();
        }
        LocalDate date = local.toLocalDate();
        return date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }

    private static OffsetDateTime bucketEndFromStart(OffsetDateTime bucketStart, ZoneId zone,
                                                     String granularity) {
        ZonedDateTime local = bucketStart.atZoneSameInstant(zone);
        return GRANULARITY_HOUR.equals(granularity)
                ? local.plusHours(1).toOffsetDateTime()
                : local.toLocalDate().plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }

    /** 稀疏毫秒直方图：键为毫秒十进制字符串，值为计数（Trace.total_ms 范围 0—600000）。 */
    public static Map<String, Long> histogramOf(Integer millis) {
        if (millis == null || millis < 0) {
            return Map.of();
        }
        Map<String, Long> histogram = new TreeMap<>();
        histogram.put(String.valueOf(millis), 1L);
        return Map.copyOf(histogram);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
