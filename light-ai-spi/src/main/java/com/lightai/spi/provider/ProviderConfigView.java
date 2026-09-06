package com.lightai.spi.provider;

import java.util.List;
import java.util.Map;

/**
 * Provider 配置视图（4.7.2.1）：validateConfig 只做本地结构校验，
 * 不访问网络和 Secret；chat/streamChat 据此构造外部请求。
 */
public record ProviderConfigView(
        String providerType,
        String baseUrl,
        String proxyUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        Map<String, String> defaultHeaders) {

    public ProviderConfigView {
        defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
    }
}
