package com.lightai.storage.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** provider_model 表行（DATABASE_PLAN §5，存储类别 C）。能力字段启用前可为空（C-014）。 */
public record ProviderModelRecord(
        UUID id,
        UUID providerId,
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
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        Integer priceUnit,
        String currency,
        boolean enabled,
        String importSource,
        String importAdapterVersion,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public ProviderModelRecord {
        defaultStop = defaultStop == null ? List.of() : List.copyOf(defaultStop);
    }
}
