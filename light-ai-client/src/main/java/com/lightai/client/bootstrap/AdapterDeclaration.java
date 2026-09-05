package com.lightai.client.bootstrap;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 已加载 Provider Adapter 的非敏感不可变声明（BACKEND_PLAN 检测与适配器元数据补充）。
 * 不含密钥、端点凭证与运行时状态。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdapterDeclaration(
        String providerType,
        String adapterVersion,
        String defaultBaseUrl,
        List<String> tokenizerFamilies,
        List<String> capabilities,
        List<ProviderOptionSpec> providerOptionSpecs) {

    public AdapterDeclaration {
        tokenizerFamilies = tokenizerFamilies == null ? List.of() : List.copyOf(tokenizerFamilies);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        providerOptionSpecs = providerOptionSpecs == null ? List.of() : List.copyOf(providerOptionSpecs);
    }
}
