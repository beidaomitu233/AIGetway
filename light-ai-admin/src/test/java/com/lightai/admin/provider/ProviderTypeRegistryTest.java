package com.lightai.admin.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.bootstrap.AdapterDeclaration;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.spi.adapter.AdapterMetadataSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Adapter 注册校验（BE-008）：内置四类 + CUSTOM_SPI 可用，
 * 未知类型 PROVIDER_ADAPTER_NOT_FOUND，部署声明可扩展。
 */
class ProviderTypeRegistryTest {

    @Test
    void builtInTypesAccepted() {
        ProviderTypeRegistry registry = new ProviderTypeRegistry(null);
        for (String type : new String[] {"OPENAI", "ANTHROPIC", "GEMINI", "DEEPSEEK", "CUSTOM_SPI"}) {
            assertThat(registry.isRegistered(type)).as(type).isTrue();
        }
        assertThat(registry.isRegistered("openai")).isFalse();
    }

    @Test
    void unknownTypeFailsWithAdapterNotFound() {
        ProviderTypeRegistry registry = new ProviderTypeRegistry(null);
        assertThatThrownBy(() -> registry.requireRegistered("MOONSHOT"))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.PROVIDER_ADAPTER_NOT_FOUND);
    }

    @Test
    void deployedAdapterDeclarationsExtendRegistry() {
        AdapterMetadataSource source = () -> List.of(new AdapterDeclaration(
                "MOONSHOT", "1.0.0", "https://api.moonshot.cn/v1",
                List.of(), List.of(), List.of()));
        ProviderTypeRegistry registry = new ProviderTypeRegistry(source);
        assertThat(registry.isRegistered("MOONSHOT")).isTrue();
        assertThat(registry.registeredTypes()).contains("MOONSHOT", "OPENAI");
    }
}
