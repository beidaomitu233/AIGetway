package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Provider Model 完整详情（4.2.6）：能力字段可能为 null（导入未知、停用阶段允许缺失），
 * 启用与发布前必须补齐（C-014）；context_window 必须大于 max_output_tokens。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProviderModelDetail(
        String id,
        String providerId,
        String providerName,
        String modelId,
        String displayName,
        String modelType,
        String tokenizerFamily,
        Long contextWindow,
        Long maxOutputTokens,
        Boolean supportStream,
        Boolean supportSystemMessage,
        Boolean supportTemperature,
        Boolean supportTopP,
        Boolean supportStop,
        BigDecimal temperatureMin,
        BigDecimal temperatureMax,
        BigDecimal topPMin,
        BigDecimal topPMax,
        Integer maxStopSequences,
        Integer maxStopLength,
        BigDecimal defaultTemperature,
        BigDecimal defaultTopP,
        Long defaultMaxTokens,
        List<String> defaultStop,
        String inputPrice,
        String outputPrice,
        Integer priceUnit,
        String currency,
        Boolean enabled,
        String importSource,
        String importAdapterVersion,
        String connectionStatus,
        OffsetDateTime lastCheckAt,
        Boolean draftChanged,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {
}
