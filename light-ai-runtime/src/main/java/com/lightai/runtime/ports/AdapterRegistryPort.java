package com.lightai.runtime.ports;

import com.lightai.client.error.LightAiException;
import com.lightai.spi.provider.ProviderAdapter;
import java.util.Optional;

/** Adapter 注册端口（BE-025 Registry，BE-055 装配接线）。 */
public interface AdapterRegistryPort {

    Optional<ProviderAdapter> adapter(String providerType);

    static LightAiException adapterNotFound(String providerType) {
        return new com.lightai.client.error.LightAiException(
                com.lightai.client.error.ErrorCode.PROVIDER_ADAPTER_NOT_FOUND,
                "Provider 类型对应的 Adapter 未加载: " + providerType);
    }
}
