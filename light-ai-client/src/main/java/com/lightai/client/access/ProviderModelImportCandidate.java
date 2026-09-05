package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 可导入模型候选（GET /admin/providers/{id}/available-models）。
 * existing 标记 provider_id + model_id 已存在；未知能力显示为空（待补充）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProviderModelImportCandidate(
        String modelId,
        String displayName,
        boolean existing,
        String source,
        String tokenizerFamily,
        Long contextWindow,
        Long maxOutputTokens,
        Boolean supportStream,
        Boolean supportSystemMessage,
        Boolean supportTemperature,
        Boolean supportTopP,
        Boolean supportStop,
        String inputPrice,
        String outputPrice,
        String currency) {
}
