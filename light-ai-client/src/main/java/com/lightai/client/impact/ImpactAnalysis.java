package com.lightai.client.impact;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 引用影响分析（BACKEND_PLAN 2 协议字典；字段对齐 FE）。
 * impact_version 由当前引用关系摘要计算（无存储票据）；
 * 停用与删除命令必须回传 confirmed_impact_version，不一致返回
 * IMPACT_ANALYSIS_EXPIRED，避免确认后引用关系已变化。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ImpactAnalysis(
        String impactVersion,
        String entityType,
        String entityId,
        List<ImpactReference> references,
        List<String> affectedAliasIds,
        boolean canDelete,
        List<String> blockers) {

    public ImpactAnalysis {
        references = references == null ? List.of() : List.copyOf(references);
        affectedAliasIds = affectedAliasIds == null ? List.of() : List.copyOf(affectedAliasIds);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
