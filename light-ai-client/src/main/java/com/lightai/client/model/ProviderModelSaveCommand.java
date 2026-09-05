package com.lightai.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * Provider Model 保存命令（BE-014；附录 4.2.6）。
 * model_id 与 provider_id 创建后不可修改；价格非负、金额精度由传输字符串保证。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderModelSaveCommand(
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

    public ProviderModelSaveCommand {
        defaultStop = defaultStop == null ? List.of() : List.copyOf(defaultStop);
    }
}
