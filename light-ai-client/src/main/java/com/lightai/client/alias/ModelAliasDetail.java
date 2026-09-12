package com.lightai.client.alias;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * Model Alias 详情（DATABASE_PLAN §6；字段对齐 FE-017/4.2.8）。
 * 运行摘要（success_rate/p95）来自 Trace 聚合（BE-P06），当前为 null。
 * request_count_24h/success_rate_24h/p95_total_ms_24h 显式声明 JSON 名：
 * Jackson SNAKE_CASE 对 "24h" 结尾只会产出 request_count24h，
 * 与 BACKEND_PLAN「24h 摘要」口径及前端/夹具的 _24h 命名不一致。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelAliasDetail(
        String id,
        String alias,
        String displayName,
        String description,
        String routeStrategy,
        long candidateCount,
        long availableCandidateCount,
        long streamCandidateCount,
        @JsonProperty("request_count_24h") long requestCount24h,
        boolean enabled,
        boolean draftChanged,
        OffsetDateTime updatedAt,
        long version,
        Long currentSnapshotNo,
        String updatedBy,
        @JsonProperty("success_rate_24h") Double successRate24h,
        @JsonProperty("p95_total_ms_24h") Long p95TotalMs24h) {
}
