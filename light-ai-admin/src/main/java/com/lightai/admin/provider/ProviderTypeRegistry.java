package com.lightai.admin.provider;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.spi.adapter.AdapterMetadataSource;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Provider 类型注册校验（BE-008：验证 Adapter 注册）。
 * V1 内置四类 Adapter 类型为产品冻结范围；CUSTOM_SPI 与部署加载的
 * Adapter 声明（AdapterMetadataSource）一并接受。类型不可写死为枚举，
 * 以便部署方注册扩展 Adapter。
 */
public final class ProviderTypeRegistry {

    public static final String CUSTOM_SPI = "CUSTOM_SPI";
    private static final Set<String> BUILT_IN =
            Set.of("OPENAI", "ANTHROPIC", "GEMINI", "DEEPSEEK");

    private final AdapterMetadataSource adapterMetadataSource;

    public ProviderTypeRegistry(AdapterMetadataSource adapterMetadataSource) {
        this.adapterMetadataSource = adapterMetadataSource;
    }

    public boolean isRegistered(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            return false;
        }
        String type = providerType.strip();
        if (BUILT_IN.contains(type) || CUSTOM_SPI.equals(type)) {
            return true;
        }
        if (adapterMetadataSource == null) {
            return false;
        }
        return adapterMetadataSource.declarations().stream()
                .anyMatch(declaration -> type.equals(declaration.providerType()));
    }

    public void requireRegistered(String providerType) {
        if (!isRegistered(providerType)) {
            throw new LightAiException(ErrorCode.PROVIDER_ADAPTER_NOT_FOUND,
                    "Provider 类型未注册任何 Adapter：" + providerType);
        }
    }

    /** 当前可见类型集合（诊断与测试使用）。 */
    public Set<String> registeredTypes() {
        Set<String> types = new LinkedHashSet<>(BUILT_IN);
        types.add(CUSTOM_SPI);
        if (adapterMetadataSource != null) {
            adapterMetadataSource.declarations()
                    .forEach(declaration -> types.add(declaration.providerType()));
        }
        return Set.copyOf(types);
    }
}
