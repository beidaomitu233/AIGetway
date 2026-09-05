package com.lightai.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;

/**
 * Provider Model 详情（DATABASE_PLAN §5；字段对齐 FE-015/附录 4.2.6）。
 * 价格以十进制字符串传输；停用导入允许能力缺失，启用与发布要求完整（C-014）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        Integer priceUnit,
        String currency,
        boolean enabled,
        String importSource,
        String importAdapterVersion,
        String connectionStatus,
        boolean draftChanged,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public ProviderModelDetail {
        defaultStop = defaultStop == null ? List.of() : List.copyOf(defaultStop);
    }

    /** 启用与发布前的能力完整性校验口径（C-014）。 */
    public boolean capabilitiesComplete() {
        return tokenizerFamily != null && !tokenizerFamily.isBlank()
                && contextWindow != null && contextWindow > 0
                && maxOutputTokens != null && maxOutputTokens > 0
                && supportStream != null && supportSystemMessage != null
                && supportTemperature != null && supportTopP != null && supportStop != null;
    }

    /** context 必须严格大于 max_output（C-014）。 */
    public boolean contextWindowValid() {
        return contextWindow == null || maxOutputTokens == null || contextWindow > maxOutputTokens;
    }
}
