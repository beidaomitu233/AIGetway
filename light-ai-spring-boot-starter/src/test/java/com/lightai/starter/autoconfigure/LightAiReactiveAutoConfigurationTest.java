package com.lightai.starter.autoconfigure;

import com.lightai.admin.LightAiAdminAutoConfiguration;
import com.lightai.admin.bootstrap.BootstrapController;
import com.lightai.client.ChatRequest;
import com.lightai.client.LightAiClient;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.storage.credential.JdbcCredentialSecretPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import javax.sql.DataSource;
import java.util.UUID;

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

    @Test
    @DisplayName("Reactive EMBEDDED 可复用管理 JDBC 存储并组装运行端口")
    void embeddedUsesJdbcStorageWithoutServletEndpoints() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LightAiAdminAutoConfiguration.class,
                        LightAiAutoConfiguration.class))
                .withBean(DataSource.class, LightAiReactiveAutoConfigurationTest::dataSource)
                .withPropertyValues(
                        "light-ai.mode=EMBEDDED",
                        "light-ai.application=reactive-jdbc-app",
                        "light-ai.storage.schema-mode=MIGRATE",
                        "light-ai.admin.runtime-mode=EMBEDDED",
                        "light-ai.admin.secret-master-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "light-ai.admin.secret-master-key-id=test-key",
                        "light-ai.admin.usage-aggregation-enabled=false",
                        "light-ai.admin.retention-cleanup-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConfigSnapshotPort.class);
                    assertThat(context).hasSingleBean(CredentialSecretPort.class);
                    assertThat(context.getBean(CredentialSecretPort.class))
                            .isInstanceOf(JdbcCredentialSecretPort.class);
                    assertThat(context).hasSingleBean(ChatPipeline.class);
                    assertThat(context).hasSingleBean(LightAiClient.class);
                    assertThat(context).doesNotHaveBean(BootstrapController.class);
                    assertThat(context.getBean(LightAiClient.class).models()).isEmpty();
                });
    }

    private static DataSource dataSource() {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:starter_reactive_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }
}
