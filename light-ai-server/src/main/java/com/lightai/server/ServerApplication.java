package com.lightai.server;

import com.lightai.admin.LightAiAdminAutoConfiguration;
import com.lightai.provider.anthropic.AnthropicAdapter;
import com.lightai.provider.deepseek.DeepSeekAdapter;
import com.lightai.provider.gemini.GeminiAdapter;
import com.lightai.provider.openai.OpenAiAdapter;
import com.lightai.runtime.capacity.InMemoryCapacityStore;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ModelsService;
import com.lightai.runtime.chat.ReliabilityBudgets;
import com.lightai.runtime.circuit.InMemoryCircuitStore;
import com.lightai.runtime.route.RouteService;
import com.lightai.runtime.trace.InMemoryTraceStore;
import com.lightai.runtime.trace.TraceStore;
import com.lightai.server.runtime.DeploymentAuthContextProvider;
import com.lightai.server.runtime.JdbcCredentialSecretPort;
import com.lightai.server.runtime.ServerAuthProperties;
import com.lightai.server.runtime.SnapshotRoutingPort;
import com.lightai.server.runtime.StoreBackedCapacityPort;
import com.lightai.spi.provider.ProviderAdapter;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Standalone Server 可执行入口（BE-056/BE-055 生产装配）：
 * 引入管理端自动装配（DataSource + 管理面 + SchemaGuard），
 * 装配真实运行链路：发布快照 → 路由 → 容量 → 凭证 → Adapter → Trace。
 * 容量共享状态为进程内原子实现（单实例语义）；集群共享存储按部署替换 CapacityStore Bean。
 */
@SpringBootApplication
@ImportAutoConfiguration(LightAiAdminAutoConfiguration.class)
@EnableConfigurationProperties(ServerAuthProperties.class)
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

    // ---------------- 管理面（LightAiAdminAutoConfiguration 提供 DataSource、
    // 管理接口、SchemaGuard、ConfigSnapshotPort、AccessTokenPort） ----------------

    @Bean
    public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
        return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    /** 部署身份适配：覆盖自动装配的默认拒绝实现（C-001 Standalone 扩展点）。 */
    @Bean
    public com.lightai.spi.auth.AuthContextProvider deploymentAuthContextProvider(
            ServerAuthProperties authProperties) {
        return new com.lightai.server.runtime.DeploymentAuthContextProvider(
                authProperties.getAdminToken(), authProperties.isTrustedLocal());
    }

    @Bean
    public OpenAiAdapter openAiAdapter() {
        return new OpenAiAdapter();
    }

    @Bean
    public AnthropicAdapter anthropicAdapter() {
        return new AnthropicAdapter();
    }

    @Bean
    public GeminiAdapter geminiAdapter() {
        return new GeminiAdapter();
    }

    @Bean
    public DeepSeekAdapter deepSeekAdapter() {
        return new DeepSeekAdapter();
    }

    @Bean
    public com.lightai.runtime.ports.AdapterRegistryPort adapterRegistryPort(List<ProviderAdapter> adapters) {
        Map<String, ProviderAdapter> byType = adapters.stream()
                .collect(Collectors.toMap(adapter -> adapter.providerType().toUpperCase(),
                        Function.identity(), (left, right) -> left));
        return type -> Optional.ofNullable(byType.get(type == null ? null : type.toUpperCase()));
    }

    /** 容量共享状态：进程内原子实现；集群部署替换为共享存储实现并保持 fail-closed 语义。 */
    @Bean
    public InMemoryCapacityStore capacityStore() {
        return new InMemoryCapacityStore();
    }

    @Bean
    public com.lightai.runtime.ports.CapacityPort capacityPort(InMemoryCapacityStore store) {
        return new StoreBackedCapacityPort(store);
    }

    @Bean
    public InMemoryCircuitStore circuitStore() {
        return new InMemoryCircuitStore();
    }

    @Bean
    public RouteService routeService() {
        return new RouteService(new SecureRandom());
    }

    @Bean
    public com.lightai.runtime.ports.RoutingPort routingPort(RouteService routeService) {
        return new SnapshotRoutingPort(routeService);
    }

    @Bean
    public com.lightai.runtime.ports.CredentialSecretPort credentialSecretPort(
            javax.sql.DataSource dataSource,
            com.lightai.storage.credential.JdbcCredentialRepository credentialRepository,
            com.lightai.storage.credential.JdbcCredentialSecretRepository secretRepository,
            com.lightai.spi.secret.SecretCipher secretCipher) {
        return new JdbcCredentialSecretPort(dataSource, credentialRepository, secretRepository, secretCipher);
    }

    /** 运行参数端口（C-010）：default_alias_id 读取自 runtime_config。 */
    @Bean
    public com.lightai.runtime.ports.AccessTokenPort.RuntimeConfigPort runtimeConfigPort(
            javax.sql.DataSource dataSource) {
        return () -> {
            String sql = "SELECT default_alias_id FROM runtime_config WHERE singleton_key = 1";
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(sql);
                 var rs = statement.executeQuery()) {
                if (rs.next()) {
                    Object aliasId = rs.getObject(1);
                    if (aliasId != null && !aliasId.toString().isBlank()) {
                        return Optional.of(aliasId.toString());
                    }
                }
                return Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        };
    }

    @Bean
    public TraceStore traceStore(javax.sql.DataSource dataSource,
                                com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort,
                                org.springframework.beans.factory.ObjectProvider<com.lightai.admin.trace.TraceFinalizer> traceFinalizerProvider,
                                org.springframework.beans.factory.ObjectProvider<com.lightai.admin.usage.UsageAggregator> usageAggregatorProvider) {
        return new com.lightai.server.runtime.JdbcTraceStore(
                dataSource,
                snapshotPort,
                traceFinalizerProvider.getIfAvailable(),
                usageAggregatorProvider.getIfAvailable(),
                java.time.Clock.systemUTC()
        );
    }

    @Bean
    public com.lightai.server.runtime.ServerInstanceCoordinator serverInstanceCoordinator(
            com.lightai.admin.publish.ConfigPublishService publishService,
            com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort) {
        return new com.lightai.server.runtime.ServerInstanceCoordinator(publishService, snapshotPort);
    }

    @Bean
    public ModelsService modelsService(com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort) {
        return new ModelsService(snapshotPort);
    }

    @Bean
    public ChatPipeline chatPipeline(com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort,
                                     com.lightai.runtime.ports.AccessTokenPort.RuntimeConfigPort runtimeConfigPort,
                                     com.lightai.runtime.ports.RoutingPort routingPort,
                                     com.lightai.runtime.ports.CapacityPort capacityPort,
                                     com.lightai.runtime.ports.CredentialSecretPort credentialPort,
                                     com.lightai.runtime.ports.AdapterRegistryPort adapterRegistry,
                                     TraceStore traceStore) {
        return new ChatPipeline(snapshotPort, runtimeConfigPort::defaultAliasId, routingPort, capacityPort,
                credentialPort, adapterRegistry, traceStore, () -> ReliabilityBudgets.DEFAULT, 120_000L);
    }

    // ---------------- Admin UI 静态资源（/ui/** → classpath:/static/ui/，深链回落 index.html） ----------------

    @Configuration
    static class AdminUiWebConfiguration implements WebMvcConfigurer {

        @Override
        public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/ui/**")
                    .addResourceLocations("classpath:/static/ui/");
            registry.addResourceHandler("/assets/**")
                    .addResourceLocations("classpath:/static/ui/assets/");
        }

        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            registry.addViewController("/").setViewName("redirect:/ui/");
            registry.addViewController("/ui").setViewName("redirect:/ui/");
            registry.addViewController("/ui/").setViewName("forward:/ui/index.html");
        }

        @Bean
        public org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver spaErrorViewResolver() {
            return (request, status, model) -> {
                if (status == org.springframework.http.HttpStatus.NOT_FOUND) {
                    Object origUri = request.getAttribute("jakarta.servlet.error.request_uri");
                    String uri = origUri != null ? origUri.toString() : request.getRequestURI();
                    if (uri != null && uri.startsWith("/ui") && !uri.startsWith("/ui/assets/")) {
                        org.springframework.web.servlet.ModelAndView mav = new org.springframework.web.servlet.ModelAndView("forward:/ui/index.html");
                        mav.setStatus(org.springframework.http.HttpStatus.OK);
                        return mav;
                    }
                }
                return null;
            };
        }
    }
}
