package com.lightai.spi.adapter;

import com.lightai.client.bootstrap.AdapterDeclaration;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从已装配的 {@link ProviderAdapter} 派生 Bootstrap 元数据（UI-ANT-CONTRACT-001）：
 * 每个进程内唯一 providerType 输出一条非敏感不可变声明（类型、默认 base_url、
 * 分词族与能力），不含密钥、凭证与运行时状态。无 Adapter 装配时输出空列表，
 * 由 BootstrapService 决定省略 adapters 字段。
 */
public final class ProviderAdapterMetadataSource implements AdapterMetadataSource {

    private final List<ProviderAdapter> adapters;

    public ProviderAdapterMetadataSource(List<ProviderAdapter> adapters) {
        this.adapters = adapters == null ? List.of() : List.copyOf(adapters);
    }

    @Override
    public List<AdapterDeclaration> declarations() {
        Map<String, AdapterDeclaration> byType = new LinkedHashMap<>();
        for (ProviderAdapter adapter : adapters) {
            String type = adapter.providerType();
            if (type == null || type.isBlank()) {
                continue;
            }
            String key = type.toUpperCase(java.util.Locale.ROOT);
            byType.putIfAbsent(key, declaration(adapter, type));
        }
        return List.copyOf(byType.values());
    }

    private AdapterDeclaration declaration(ProviderAdapter adapter, String providerType) {
        AdapterCapabilities capabilities = adapter.capabilities();
        return new AdapterDeclaration(
                providerType,
                null,
                adapter.defaultBaseUrl(),
                capabilities == null ? List.of() : capabilities.tokenizerFamilies(),
                capabilityNames(capabilities),
                capabilities == null ? List.of() : capabilities.optionSpecs());
    }

    /** 能力名沿用 Bootstrap 契约测试基线（CHAT/STREAM/SYSTEM_MESSAGE/MODEL_LIST）。 */
    private static List<String> capabilityNames(AdapterCapabilities capabilities) {
        if (capabilities == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        if (capabilities.supportsChat()) {
            names.add("CHAT");
        }
        if (capabilities.supportsStream()) {
            names.add("STREAM");
        }
        if (capabilities.supportsSystemMessage()) {
            names.add("SYSTEM_MESSAGE");
        }
        if (capabilities.supportsModelList()) {
            names.add("MODEL_LIST");
        }
        return List.copyOf(names);
    }
}
