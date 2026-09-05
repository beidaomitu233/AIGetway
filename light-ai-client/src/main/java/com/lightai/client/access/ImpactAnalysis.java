package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 影响分析结果（4.2.9.5）：impact_version 由当前引用关系摘要计算，
 * 停用/删除命令必须回传同一值，引用变化即 IMPACT_ANALYSIS_EXPIRED。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ImpactAnalysis(
        String impactVersion,
        String entityType,
        String entityId,
        List<Reference> references,
        List<String> affectedAliasIds,
        boolean canDelete,
        List<String> blockers) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Reference(String entityType, String id, String name, String relation) {
    }
}
