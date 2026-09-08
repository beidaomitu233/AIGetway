package com.lightai.starter.autoconfigure;

import com.lightai.client.ChatRequest;
import com.lightai.client.LightAiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BE-055/059: Reactive Web 自动装配兼容性")
class LightAiReactiveAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withClassLoader(new FilteredClassLoader(
                            "jakarta.servlet",
                            "org.springframework.web.servlet"))
                    .withConfiguration(AutoConfigurations.of(LightAiAutoConfiguration.class));

    @Test
    @DisplayName("Reactive 宿主的 STANDALONE_CLIENT 模式只装配远程客户端")
    void standaloneClientLoadsWithoutServletClasses() {
        contextRunner.withPropertyValues(
                        "light-ai.mode=STANDALONE_CLIENT",
                        "light-ai.client.base-url=http://localhost:8080",
                        "light-ai.client.access-token=test-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LightAiClient.class);
                    assertThat(context).hasBean("standaloneLightAiClient");
                    assertThat(context).doesNotHaveBean("embeddedLightAiClient");
                });
    }

    @Test
    @DisplayName("Reactive 宿主的 EMBEDDED 模式可完成进程内模型查询与 Chat")
    void embeddedLoadsWithoutServletClasses() {
        contextRunner.withUserConfiguration(EmbeddedRuntimeTestConfiguration.class)
                .withPropertyValues(
                        "light-ai.mode=EMBEDDED",
                        "light-ai.application=reactive-app",
                        "light-ai.admin.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LightAiClient.class);
                    assertThat(context).hasBean("embeddedLightAiClient");
                    assertThat(context).doesNotHaveBean("standaloneLightAiClient");
                    assertThat(context).doesNotHaveBean("embeddedAdminSecurityFilterRegistration");
                    LightAiClient client = context.getBean(LightAiClient.class);
                    assertThat(client.models()).extracting("id").containsExactly("demo");
                    assertThat(client.chat(ChatRequest.builder()
                                    .model("demo")
                                    .addUserMessage("hello")
                                    .build()).content())
                            .isEqualTo("embedded response");
                });
    }
}
