package com.lightai.starter.autoconfigure;

import com.lightai.client.LightAiClient;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderStreamChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BE-055: Starter 自动装配与条件规则测试")
class LightAiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LightAiAutoConfiguration.class));

    @Test
    @DisplayName("light-ai.enabled=false 时不创建任何 Light AI Bean")
    void disabledWhenConfiguredFalse() {
        contextRunner.withPropertyValues("light-ai.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LightAiClient.class);
                });
    }

    @Test
    @DisplayName("默认 STANDALONE_CLIENT 模式装配客户端且不加载嵌入式组件")
    void defaultStandaloneClientMode() {
        contextRunner.withPropertyValues(
                "light-ai.client.base-url=http://localhost:8080",
                "light-ai.client.access-token=test-token"
        ).run(context -> {
            assertThat(context).hasSingleBean(LightAiClient.class);
            assertThat(context).hasBean("standaloneLightAiClient");
        });
    }

    @Test
    @DisplayName("宿主提供同名/同类型 LightAiClient 时由宿主覆盖 (ConditionalOnMissingBean)")
    void hostBeanOverridesDefaultClient() {
        contextRunner.withUserConfiguration(CustomClientConfiguration.class)
                .withPropertyValues("light-ai.client.base-url=http://localhost:8080")
                .run(context -> {
                    assertThat(context).hasSingleBean(LightAiClient.class);
                    LightAiClient client = context.getBean(LightAiClient.class);
                    assertThat(client.isClosed()).isFalse();
                });
    }

    @Test
    @DisplayName("EMBEDDED 模式缺少 application 时阻止启动")
    void embeddedModeRequiresApplication() {
        contextRunner.withPropertyValues("light-ai.mode=EMBEDDED")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getCause())
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context.getStartupFailure())
                            .getRootCause()
                            .hasMessageContaining("light-ai.application 必须在 EMBEDDED 模式下配置");
                });
    }

    @Test
    @DisplayName("EMBEDDED 模式装配成功且支持 Web Admin 安全配置")
    void embeddedModeSucceedsWithApplication() {
        contextRunner.withPropertyValues(
                "light-ai.mode=EMBEDDED",
                "light-ai.application=my-app",
                "light-ai.admin.path=/light-ai/admin",
                "light-ai.admin.local-access-enabled=true"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LightAiClient.class);
            assertThat(context).hasBean("embeddedLightAiClient");
        });
    }

    @Test
    @DisplayName("EMBEDDED 模式存在两个相同 provider_type 的 Adapter 时启动失败并指出冲突")
    void duplicateProviderTypeFails() {
        contextRunner.withUserConfiguration(DuplicateAdapterConfiguration.class)
                .withPropertyValues(
                        "light-ai.mode=EMBEDDED",
                        "light-ai.application=my-app"
                ).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .getRootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("检测到冲突的 ProviderAdapter provider_type: OPENAI");
                });
    }

    @Test
    @DisplayName("Admin 路径与保留业务路径冲突时抛出 ADMIN_PATH_CONFLICT")
    void adminPathConflictFails() {
        contextRunner.withPropertyValues(
                "light-ai.mode=EMBEDDED",
                "light-ai.application=my-app",
                "light-ai.admin.path=/v1"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .getRootCause()
                    .isInstanceOf(LightAiException.class);
            LightAiException ex = (LightAiException) org.assertj.core.util.Throwables.getRootCause(context.getStartupFailure());
            assertThat(ex.code()).isEqualTo(ErrorCode.ADMIN_PATH_CONFLICT);
        });
    }

    @Configuration
    static class CustomClientConfiguration {
        @Bean
        public LightAiClient customClient() {
            return LightAiClient.builder()
                    .baseUrl("http://custom-host:9090")
                    .token("custom-token")
                    .build();
        }
    }

    @Configuration
    static class DuplicateAdapterConfiguration {
        @Bean
        public ProviderAdapter adapterOne() {
            return new DummyAdapter("OPENAI");
        }

        @Bean
        public ProviderAdapter adapterTwo() {
            return new DummyAdapter("OPENAI");
        }
    }

    static class DummyAdapter implements ProviderAdapter {
        private final String type;
        DummyAdapter(String type) { this.type = type; }
        @Override public String providerType() { return type; }
        @Override public AdapterCapabilities capabilities() { return null; }
        @Override public long estimateTokens(ProviderChatRequest request) { return 0; }
        @Override public ProviderChatResponse chat(ProviderCallContext context) { return null; }
        @Override public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) { return null; }
        @Override public ProviderErrorClassification classifyError(ProviderFailure failure) { return null; }
    }
}
