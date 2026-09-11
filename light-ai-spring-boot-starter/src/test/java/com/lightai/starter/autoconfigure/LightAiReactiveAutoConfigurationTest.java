package com.lightai.starter.autoconfigure;

import com.lightai.admin.LightAiAdminAutoConfiguration;
import com.lightai.admin.LightAiReactiveAdminAutoConfiguration;
import com.lightai.admin.bootstrap.BootstrapController;
import com.lightai.admin.web.ReactiveAdminWebFilter;
import com.lightai.admin.web.ReactiveBootstrapController;
import com.lightai.client.json.ProtocolJson;
import com.lightai.client.ChatRequest;
import com.lightai.client.LightAiClient;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.storage.channel.JdbcChannelCredentialSecretPort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

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
                        LightAiReactiveAdminAutoConfiguration.class,
                        LightAiAutoConfiguration.class))
                .withBean(AuthContextProvider.class,
                        LightAiReactiveAutoConfigurationTest::authenticatedAdmin)
                .withBean(DataSource.class, LightAiReactiveAutoConfigurationTest::dataSource)
                .withPropertyValues(
                        "light-ai.mode=EMBEDDED",
                        "light-ai.application=reactive-jdbc-app",
                        "light-ai.storage.schema-mode=MIGRATE",
                        "light-ai.admin.runtime-mode=EMBEDDED",
                        "light-ai.admin.secret-master-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "light-ai.admin.secret-master-key-id=test-key",
                        "light-ai.admin.csrf-enabled=true",
                        "light-ai.admin.usage-aggregation-enabled=false",
                        "light-ai.admin.retention-cleanup-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConfigSnapshotPort.class);
                    assertThat(context).hasSingleBean(CredentialSecretPort.class);
                    assertThat(context.getBean(CredentialSecretPort.class))
                            .isInstanceOf(JdbcChannelCredentialSecretPort.class);
                    assertThat(context).hasSingleBean(ChatPipeline.class);
                    assertThat(context).hasSingleBean(LightAiClient.class);
                    assertThat(context).doesNotHaveBean(BootstrapController.class);
                    assertThat(context).hasSingleBean(ReactiveBootstrapController.class);
                    assertThat(context).hasSingleBean(ReactiveAdminWebFilter.class);
                    assertThat(context.getBean(LightAiClient.class).models()).isEmpty();

                    WebTestClient client = WebTestClient.bindToController(
                                    context.getBean(ReactiveBootstrapController.class),
                                    new ReactiveDummyWriteController())
                            .webFilter(context.getBean(ReactiveAdminWebFilter.class))
                            .build();
                    EntityExchangeResult<byte[]> bootstrap = client.get()
                            .uri("/admin/bootstrap")
                            .header("X-Request-Id", "reactive-request-1")
                            .exchange()
                            .expectStatus().isOk()
                            .expectHeader().valueEquals("X-Request-Id", "reactive-request-1")
                            .expectBody()
                            .jsonPath("$.data.user.id").isEqualTo("reactive-admin")
                            .jsonPath("$.data.csrf_token").isNotEmpty()
                            .returnResult();

                    ResponseCookie session = bootstrap.getResponseCookies().getFirst("SESSION");
                    assertThat(session).isNotNull();
                    String csrfToken;
                    try {
                        csrfToken = ProtocolJson.protocol()
                                .readTree(new String(bootstrap.getResponseBody(), StandardCharsets.UTF_8))
                                .path("data").path("csrf_token").asText();
                    } catch (java.io.IOException e) {
                        throw new AssertionError("响应式 bootstrap JSON 解析失败", e);
                    }
                    assertThat(csrfToken).isNotBlank();

                    client.post().uri("/admin/dummy")
                            .cookie(session.getName(), session.getValue())
                            .exchange()
                            .expectStatus().isForbidden()
                            .expectBody()
                            .jsonPath("$.error.code").isEqualTo("ACCESS_DENIED");
                    client.post().uri("/admin/dummy")
                            .cookie(session.getName(), session.getValue())
                            .header("X-CSRF-Token", csrfToken)
                            .exchange()
                            .expectStatus().isOk()
                            .expectBody(String.class).isEqualTo("written");
                });
    }

    @Test
    @DisplayName("Reactive 管理入口缺少宿主身份时默认拒绝")
    void reactiveAdminDeniesAnonymousAccess() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LightAiAdminAutoConfiguration.class,
                        LightAiReactiveAdminAutoConfiguration.class))
                .withPropertyValues("light-ai.admin.runtime-mode=EMBEDDED")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WebTestClient.bindToController(context.getBean(ReactiveBootstrapController.class))
                            .webFilter(context.getBean(ReactiveAdminWebFilter.class))
                            .build()
                            .get().uri("/admin/bootstrap")
                            .exchange()
                            .expectStatus().isForbidden()
                            .expectHeader().exists("X-Request-Id")
                            .expectBody()
                            .jsonPath("$.error.code").isEqualTo("ACCESS_DENIED")
                            .jsonPath("$.error.request_id").isNotEmpty();
                });
    }

    private static DataSource dataSource() {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:starter_reactive_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }

    private static AuthContextProvider authenticatedAdmin() {
        return request -> AuthContext.authenticated(
                "reactive-admin", "Reactive Admin", Set.of("SYSTEM_ADMIN"),
                List.of("*"), List.of("*"));
    }

    @RestController
    static class ReactiveDummyWriteController {
        @PostMapping("/admin/dummy")
        String write() {
            return "written";
        }
    }
}
