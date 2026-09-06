package com.lightai.spi.provider;

import com.lightai.client.bootstrap.ProviderOptionSpec;
import java.util.List;
import java.util.Set;

/**
 * Adapter 能力声明（4.7.2.1）：启动后不可变化，供表单、发布和运行过滤使用。
 */
public record AdapterCapabilities(
        boolean supportsChat,
        boolean supportsStream,
        boolean supportsSystemMessage,
        boolean supportsModelList,
        List<String> tokenizerFamilies,
        Integer maxStopSequences,
        Set<String> finishReasons,
        List<ProviderOptionSpec> optionSpecs) {

    public AdapterCapabilities {
        tokenizerFamilies = tokenizerFamilies == null ? List.of() : List.copyOf(tokenizerFamilies);
        finishReasons = finishReasons == null ? Set.of() : Set.copyOf(finishReasons);
        optionSpecs = optionSpecs == null ? List.of() : List.copyOf(optionSpecs);
    }
}
