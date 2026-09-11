package com.lightai.client.upstream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * Provider Model 保存命令（BE-014；附录 4.2.6）。
 * model_id 与 channel_id 创建后不可修改；价格非负、金额精度由传输字符串保证。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpstreamModelSaveCommand(
        @JsonProperty("channel_id") String channelId,
        @JsonProperty("model_id") String modelId,
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
        @JsonProperty("top_p_min") BigDecimal topPMin,
        @JsonProperty("top_p_max") BigDecimal topPMax,
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
        Long version) {

    public UpstreamModelSaveCommand {
        defaultStop = defaultStop == null ? List.of() : List.copyOf(defaultStop);
    }

    public UpstreamModelSaveCommand(
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
            boolean enabled,
            Long version) {
        this(null, null, displayName, tokenizerFamily, contextWindow, maxOutputTokens,
                supportStream, supportSystemMessage, supportTemperature, supportTopP, supportStop,
                temperatureMin, temperatureMax, topPMin, topPMax, maxStopSequences, maxStopLength,
                defaultTemperature, defaultTopP, defaultMaxTokens, defaultStop, inputPrice, outputPrice,
                priceUnit, currency, enabled, version);
    }
}
