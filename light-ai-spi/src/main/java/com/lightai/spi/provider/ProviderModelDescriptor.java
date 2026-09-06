package com.lightai.spi.provider;

import java.util.List;

/** 外部模型列表条目（4.7.2.1 listModels）。 */
public record ProviderModelDescriptor(
        String modelId,
        String displayName,
        String tokenizerFamily,
        Long contextWindow,
        Long maxOutputTokens,
        Boolean supportStream) {

    public static ProviderModelDescriptor minimal(String modelId) {
        return new ProviderModelDescriptor(modelId, modelId, null, null, null, null);
    }
}
