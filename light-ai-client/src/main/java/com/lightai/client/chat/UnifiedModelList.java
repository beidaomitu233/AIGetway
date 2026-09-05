package com.lightai.client.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /v1/models 响应：object 固定 list，data 按 id 升序，不分页；
 * 不创建业务 Trace。light_ai 携带 Alias 能力与范围（4.7.1.2）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnifiedModelList(String object, List<ModelSummary> data) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ModelSummary(
            String id,
            String object,
            long created,
            String ownedBy,
            LightAiModelInfo lightAi) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LightAiModelInfo(
            String displayName,
            Boolean supportStream,
            Boolean supportSystem,
            BigDecimal temperatureMin,
            BigDecimal temperatureMax,
            BigDecimal topPMin,
            BigDecimal topPMax,
            Integer stopMax,
            Long contextWindow,
            Long maxOutputTokens,
            java.time.OffsetDateTime updatedAt) {
    }
}
