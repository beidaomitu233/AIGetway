package com.lightai.client.alias;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Model Alias 详情（DATABASE_PLAN §6；字段对齐 FE-017/4.2.8）。
 * 运行摘要（success_rate/p95）来自 Trace 聚合（BE-P06），当前为 null。
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
        long requestCount24h,
        boolean enabled,
        boolean draftChanged,
        OffsetDateTime updatedAt,
        long version,
        Long currentSnapshotNo,
        String updatedBy,
        Double successRate24h,
        Long p95TotalMs24h) {
}
