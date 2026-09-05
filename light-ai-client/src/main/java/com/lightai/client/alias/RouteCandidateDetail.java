package com.lightai.client.alias;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 候选详情（BACKEND_PLAN 4.2.9.4；字段对齐 FE-018）。
 * runtime_status 由配置与运行状态派生（容量与熔断维度由 BE-P04 补全），
 * 不进入可写 DTO。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RouteCandidateDetail(
        String id,
        String aliasId,
        String providerId,
        String providerName,
        String providerModelId,
        String providerModelDisplayName,
        String providerModelIdLabel,
        String credentialPoolId,
        String credentialPoolName,
        int priority,
        int weight,
        boolean enabled,
        Boolean supportStream,
        Boolean supportSystemMessage,
        Long contextWindow,
        long currentConcurrency,
        String runtimeStatus,
        String excludedReason,
        boolean draftChanged,
        long version,
        OffsetDateTime updatedAt) {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATUS_CAPACITY_EXHAUSTED = "CAPACITY_EXHAUSTED";
    public static final String STATUS_CIRCUIT_OPEN = "CIRCUIT_OPEN";
}
