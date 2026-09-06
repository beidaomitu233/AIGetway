package com.lightai.client.overview;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运行概览契约对象（BE-034；字段对齐 FE-031/032/033 与 BACKEND_PLAN 4.4.4）。
 * 成功率分母为 SUCCEEDED+FAILED+STREAM_INTERRUPTED；多币种不做跨币种总额（C-009）。
 */
public final class OverviewResults {

    private OverviewResults() {
    }

    /** 概览筛选选项；providers 在指定 alias 时收敛为该 Alias 候选使用的 Provider。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OverviewFilterOptions(
            List<String> applications,
            List<OverviewOptionRef> aliases,
            List<OverviewOptionRef> providers,
            List<String> currencies) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OverviewOptionRef(String id, String name) {
    }

    /** 分币种金额；不产生跨币种合计。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AmountByCurrency(String currency, BigDecimal amount) {
    }

    /**
     * 概览摘要。invalidCredentialCount 仅系统管理员/运维人员可见，
     * 其他角色该字段为 null 且序列化时省略（FE-033）。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OverviewSummary(
            long requestCount,
            long successCount,
            long failureCount,
            long streamInterruptedCount,
            long cancelledCount,
            long activeCount,
            BigDecimal successRate,
            BigDecimal averageTotalMs,
            Long p95FirstTokenMs,
            long totalTokens,
            long actualTokens,
            long estimatedTokens,
            List<AmountByCurrency> costs,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount,
            long openCircuitCount,
            long unavailableCandidateCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long invalidCredentialCount,
            OffsetDateTime dataUpdatedAt) {
    }

    /**
     * 概览趋势。points 按桶升序且连续无重复；无数据桶补零，
     * 成功率分母为 0 与无流式样本的 P95 返回 null，不显示为真实 0。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OverviewTrendResult(
            OffsetDateTime dataUpdatedAt,
            String granularity,
            List<String> currencies,
            List<OverviewTrendPoint> points) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OverviewTrendPoint(
            OffsetDateTime bucketStart,
            OffsetDateTime bucketEnd,
            long requestCount,
            long successCount,
            long failureCount,
            BigDecimal successRate,
            BigDecimal averageTotalMs,
            Long p95FirstTokenMs,
            long totalTokens,
            long retryCount,
            long fallbackCount,
            List<AmountByCurrency> costs) {
    }

    /** 概览异常摘要；invalidCredentialCount 同摘要规则按角色裁剪。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OverviewExceptionSummary(
            long openCircuitCount,
            long halfOpenCircuitCount,
            long unavailableCandidateCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long invalidCredentialCount,
            long recentFailureTraceCount) {
    }

    /**
     * 异常列表项。item_type：CIRCUIT、CANDIDATE、CREDENTIAL、TRACE；
     * 开发人员与只读人员不返回 CREDENTIAL 项（RV-003）。
     * object_id 指向已删除对象时使用名称快照，只允许进入 Trace 详情。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OverviewExceptionItem(
            String itemType,
            String objectId,
            String objectName,
            String status,
            String errorCode,
            String errorSummary,
            long occurrenceCount,
            OffsetDateTime latestAt,
            String relatedProviderName,
            String relatedModelName,
            String relatedAliasName) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OverviewExceptionResult(
            OffsetDateTime dataUpdatedAt,
            OverviewExceptionSummary summary,
            List<OverviewExceptionItem> items) {
    }
}
