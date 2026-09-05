package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Route Candidate 详情（4.2.8.2）：能力字段由关联模型组合计算；
 * 实时并发与 runtime_status 不进入草稿，本视图只输出配置侧字段。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RouteCandidateDetail(
        String id,
        String aliasId,
        String providerModelId,
        String providerName,
        String providerModelName,
        String modelId,
        String credentialPoolId,
        String credentialPoolName,
        int priority,
        int weight,
        Boolean enabled,
        Boolean supportStream,
        Boolean supportSystemMessage,
        Long contextWindow,
        Boolean draftChanged,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {
}
