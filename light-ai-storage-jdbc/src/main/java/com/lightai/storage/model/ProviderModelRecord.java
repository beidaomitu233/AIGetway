package com.lightai.storage.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * provider_model 表行（DATABASE_PLAN §5，C类）。
 * 能力与范围字段可为 null（导入未知、停用阶段）；启用与发布前必须补齐（C-014）。
 */
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
        int priceUnit,
        String currency,
        boolean enabled,
        String importSource,
        String importAdapterVersion,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {

    public boolean alive() {
        return deletedAt == null;
    }
}
