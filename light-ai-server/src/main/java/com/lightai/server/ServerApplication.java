package com.lightai.server;

import com.lightai.admin.LightAiAdminAutoConfiguration;
import com.lightai.provider.anthropic.AnthropicAdapter;
import com.lightai.provider.deepseek.DeepSeekAdapter;
import com.lightai.provider.gemini.GeminiAdapter;
import com.lightai.provider.openai.OpenAiAdapter;
import com.lightai.runtime.capacity.CapacityStore;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ModelsService;
import com.lightai.runtime.chat.ReliabilityBudgets;
import com.lightai.runtime.route.RouteService;
import com.lightai.runtime.trace.InMemoryTraceStore;
import com.lightai.runtime.trace.TraceStore;
import com.lightai.server.runtime.DeploymentAuthContextProvider;
import com.lightai.storage.credential.JdbcCredentialSecretPort;
import com.lightai.server.runtime.ServerAuthProperties;
import com.lightai.server.runtime.SnapshotRoutingPort;
import com.lightai.runtime.capacity.StoreBackedCapacityPort;
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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Standalone Server 可执行入口（BE-056/BE-055 生产装配）：
 * 引入管理端自动装配（DataSource + 管理面 + SchemaGuard），
 * 装配真实运行链路：发布快照 → 路由 → 容量 → 凭证 → Adapter → Trace。
 * 容量、熔断与 FIFO 队列统一使用 Redis 共享状态，支持 Standalone 多实例一致性。
 */
@SpringBootApplication
@ImportAutoConfiguration(LightAiAdminAutoConfiguration.class)
@EnableConfigurationProperties(ServerAuthProperties.class)
@EnableScheduling
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
            ServerAuthProperties authProperties, com.lightai.admin.AdminProperties adminProperties) {
        // Provider 连接边界的内网校验与管理面 TargetUrlPolicy 使用同一部署开关
        com.lightai.spi.provider.ProviderNetworkPolicies.configure(
                adminProperties.isAllowedProviderInternalNetworks());
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

    /** Standalone 强制使用 Redis 共享容量状态，连接不可用时启动或预占 fail-closed。 */
    @Bean(destroyMethod = "close")
    public com.lightai.storage.redis.RedisCapacityStore capacityStore(
            @org.springframework.beans.factory.annotation.Value("${light-ai.redis.uri:redis://127.0.0.1:6379/0}")
            String redisUri,
            @org.springframework.beans.factory.annotation.Value("${light-ai.redis.namespace:light-ai}")
            String namespace,
            @org.springframework.beans.factory.annotation.Value("${light-ai.redis.capacity-lease-ms:150000}")
            long leaseMillis) {
        return new com.lightai.storage.redis.RedisCapacityStore(redisUri, namespace, leaseMillis);
    }

    @Bean
    public com.lightai.runtime.ports.CapacityPort capacityPort(CapacityStore store) {
        return new StoreBackedCapacityPort(store);
    }

    @Bean(destroyMethod = "close")
    public com.lightai.storage.redis.RedisCircuitStateStore circuitStore(
            @org.springframework.beans.factory.annotation.Value("${light-ai.redis.uri:redis://127.0.0.1:6379/0}")
            String redisUri,
            @org.springframework.beans.factory.annotation.Value("${light-ai.redis.namespace:light-ai}")
            String namespace) {
        return new com.lightai.storage.redis.RedisCircuitStateStore(redisUri, namespace);
    }

    @Bean(destroyMethod = "close")
    public com.lightai.storage.redis.RedisFifoQueue queueService(
            @org.springframework.beans.factory.annotation.Value("${light-ai.redis.uri:redis://127.0.0.1:6379/0}")
            String redisUri,
            @org.springframework.beans.factory.annotation.Value("${light-ai.redis.namespace:light-ai}")
            String namespace,
            @org.springframework.beans.factory.annotation.Value("${light-ai.redis.queue-default-max-size:1000}")
            int defaultMaxSize) {
        return new com.lightai.storage.redis.RedisFifoQueue(redisUri, namespace, defaultMaxSize);
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
                                     com.lightai.runtime.circuit.CircuitStateStore circuitStateStore,
                                     com.lightai.runtime.capacity.QueueService queueService,
                                     com.lightai.runtime.ports.CredentialSecretPort credentialPort,
                                     com.lightai.runtime.ports.AdapterRegistryPort adapterRegistry,
                                     TraceStore traceStore,
                                     com.lightai.runtime.ports.ApplicationQuotaPort applicationQuotaPort) {
        return new ChatPipeline(snapshotPort, runtimeConfigPort::defaultAliasId, routingPort, capacityPort,
                circuitStateStore, credentialPort, queueService, adapterRegistry, traceStore,
                applicationQuotaPort, () -> ReliabilityBudgets.DEFAULT, 120_000L);
    }

    // ---------------- Admin UI 静态资源（/ui/** → classpath:/static/ui/，深链回落 index.html） ----------------

    @Configuration
    static class AdminUiWebConfiguration implements WebMvcConfigurer {

        @Override
        public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/ui/**")
                    .addResourceLocations("classpath:/static/ui/")
                    .resourceChain(true)
                    .addResolver(new org.springframework.web.servlet.resource.PathResourceResolver() {
                        @Override
                        protected org.springframework.core.io.Resource getResource(String resourcePath, org.springframework.core.io.Resource location) throws java.io.IOException {
                            org.springframework.core.io.Resource requestedResource = location.createRelative(resourcePath);
                            if (requestedResource.exists() && requestedResource.isReadable()) {
                                return requestedResource;
                            }
                            if (resourcePath.contains("assets/")) {
                                String subPath = resourcePath.substring(resourcePath.indexOf("assets/"));
                                org.springframework.core.io.Resource assetResource = location.createRelative(subPath);
                                if (assetResource.exists() && assetResource.isReadable()) {
                                    return assetResource;
                                }
                            }
                            if (!resourcePath.startsWith("assets/") && !resourcePath.contains(".")) {
                                return location.createRelative("index.html");
                            }
                            return null;
                        }
                    });
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
                    if (uri != null && uri.startsWith("/ui") && !uri.contains("/assets/")) {
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
