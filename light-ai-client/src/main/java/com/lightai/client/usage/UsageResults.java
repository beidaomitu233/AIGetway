package com.lightai.client.usage;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Usage 与 Cost 契约对象（BE-035；字段对齐 FE-034/035 与 BACKEND_PLAN 4.4.4.2）。
 * 四个接口对同一组筛选字段返回相同 query_fingerprint；
 * 未指定 currency 时费用按币种拆行/拆序列，不做跨币种合计（C-009）。
 */
public final class UsageResults {

    private UsageResults() {
    }

    /** 分币种费用三元组；金额八位小数字符串传输。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AmountCost(String currency, BigDecimal inputCost, BigDecimal outputCost,
                             BigDecimal totalCost) {
    }

    /** Usage 摘要；指定单一 currency 时顶层金额字段有值，多币种时只读 costs。
     *  startAt/endAt/timezone 为服务端解析并对齐后的查询口径，供三接口一致性核对。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsageSummaryResult(
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String timezone,
            long requestCount,
            long successCount,
            long failureCount,
            long cancelledCount,
            long queuedCount,
            long streamCount,
            long streamInterruptedCount,
            BigDecimal successRate,
            long attemptCount,
            long initialCount,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount,
            long halfOpenProbeCount,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long actualTokens,
            long estimatedTokens,
            BigDecimal actualTokenRate,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal inputCost,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal outputCost,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal totalCost,
            List<AmountCost> costs,
            OffsetDateTime dataUpdatedAt,
            String queryFingerprint) {
    }

    /**
     * Usage 趋势；points 按桶升序连续无重复，缺失桶补零
     * （多币种时每个币种单独补零）。startAt/endAt/timezone 为解析后查询口径。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsageTrendResult(
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String timezone,
            String granularity,
            List<String> currencies,
            OffsetDateTime dataUpdatedAt,
            String queryFingerprint,
            List<UsageTrendPoint> points) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsageTrendPoint(
            OffsetDateTime bucketStart,
            OffsetDateTime bucketEnd,
            long requestCount,
            long successCount,
            long failureCount,
            BigDecimal successRate,
            long attemptCount,
            long initialCount,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount,
            long halfOpenProbeCount,
            long actualTokens,
            long estimatedTokens,
            long totalTokens,
            List<AmountCost> costs) {
    }

    /**
     * Usage 分组行。未指定 currency 时按 dimension 与 currency 拆行；
     * cost_share 仅在指定单一 currency 时计算，否则为 null。
     * 维度对象已删除时 dimension_name 使用聚合保存的历史名称，空维度显示「未设置」。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsageGroupRow(
            String dimensionType,
            String dimensionId,
            String dimensionName,
            String currency,
            long requestCount,
            long successCount,
            long failureCount,
            BigDecimal successRate,
            long attemptCount,
            long initialCount,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount,
            long halfOpenProbeCount,
            long actualTokens,
            long estimatedTokens,
            long totalTokens,
            BigDecimal inputCost,
            BigDecimal outputCost,
            BigDecimal totalCost,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal requestShare,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal tokenShare,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal costShare) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsageGroupResult(
            String dimensionType,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String timezone,
            long total,
            int page,
            int pageSize,
            OffsetDateTime dataUpdatedAt,
            String queryFingerprint,
            List<UsageGroupRow> groups) {
    }
}
