package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * Provider Model 新建/编辑命令（4.2.9.3）。
 * provider_id/model_id 仅创建时有效，编辑提交也会被忽略（模型归属不可改）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProviderModelCommand(
        String providerId,
        String modelId,
        String displayName,
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
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        Integer priceUnit,
        String currency,
        Boolean enabled,
        Long version) {
}
