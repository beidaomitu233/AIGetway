package com.lightai.starter.autoconfigure;

import com.lightai.client.LightAiClient;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthRequest;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.secret.SecretProvider;
import com.lightai.starter.properties.SpringLightAiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMBEDDED 模式自动装配（PRD 4.6.3.1，BE-055）：
 * 绑定宿主配置、校验数据结构、加载快照、收集 Adapter 与 SPI 扩展；
 * 挂载 Embedded Admin UI 与安全鉴权过滤。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "light-ai", name = "mode", havingValue = "EMBEDDED")
public class LightAiEmbeddedConfiguration {

    public LightAiEmbeddedConfiguration(SpringLightAiProperties properties,
                                        ObjectProvider<List<ProviderAdapter>> adaptersProvider) {
        // 1. 校验 application 必填
        if (properties.getApplication() == null || properties.getApplication().isBlank()) {
            throw new IllegalStateException("light-ai.application 必须在 EMBEDDED 模式下配置");
        }

        // 2. 校验 ProviderAdapter 重复类型拦截（PRD 4.6.3.1）
        List<ProviderAdapter> adapters = adaptersProvider.getIfAvailable(ArrayList::new);
        Map<String, String> typeToBean = new HashMap<>();
        for (ProviderAdapter adapter : adapters) {
            String type = adapter.providerType();
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("ProviderAdapter provider_type 不能为空");
            }
            type = type.toUpperCase(java.util.Locale.ROOT);
            String beanName = adapter.getClass().getSimpleName();
            if (typeToBean.containsKey(type)) {
                throw new IllegalStateException("检测到冲突的 ProviderAdapter provider_type: " + type
                        + "，冲突 Bean 名称: [" + typeToBean.get(type) + ", " + beanName + "]");
            }
            typeToBean.put(type, beanName);
        }

        // 3. 校验 Admin 路径冲突（PRD 4.6.3.4）
        String adminPath = properties.getAdmin().getPath();
        if (adminPath != null) {
            if (adminPath.equals("/") || adminPath.equals("/v1") || adminPath.equals("/api")) {
                throw new LightAiException(ErrorCode.ADMIN_PATH_CONFLICT,
                        "Admin 路径与宿主保留路径冲突: " + adminPath);
            }
        }
    }

    @Bean
    @ConditionalOnBean({
            javax.sql.DataSource.class,
            com.lightai.storage.channel.JdbcChannelCredentialRepository.class,
            com.lightai.spi.secret.SecretCipher.class
    })
    @ConditionalOnMissingBean({
            com.lightai.runtime.chat.ChatPipeline.class,
            com.lightai.runtime.ports.CredentialSecretPort.class
    })
    public com.lightai.runtime.ports.CredentialSecretPort embeddedCredentialSecretPort(
            javax.sql.DataSource dataSource,
            com.lightai.storage.channel.JdbcChannelCredentialRepository credentialRepository,
            com.lightai.spi.secret.SecretCipher secretCipher) {
        return new com.lightai.storage.channel.JdbcChannelCredentialSecretPort(
                dataSource, credentialRepository, secretCipher);
    }

    @Bean
    @ConditionalOnMissingBean(com.lightai.runtime.chat.ChatPipeline.class)
    public com.lightai.runtime.chat.ChatPipeline embeddedChatPipeline(
            ObjectProvider<com.lightai.runtime.ports.ConfigSnapshotPort> snapshotPortProvider,
            ObjectProvider<com.lightai.runtime.ports.CredentialSecretPort> credentialPortProvider,
            ObjectProvider<com.lightai.runtime.ports.AccessTokenPort.RuntimeConfigPort> runtimeConfigPortProvider,
            ObjectProvider<com.lightai.runtime.ports.RoutingPort> routingPortProvider,
            ObjectProvider<com.lightai.runtime.ports.CapacityPort> capacityPortProvider,
            ObjectProvider<com.lightai.runtime.circuit.CircuitStateStore> circuitStateStoreProvider,
            ObjectProvider<com.lightai.runtime.capacity.QueueService> queueServiceProvider,
            ObjectProvider<com.lightai.runtime.ports.AdapterRegistryPort> adapterRegistryPortProvider,
            ObjectProvider<com.lightai.runtime.trace.TraceStore> traceStoreProvider,
            ObjectProvider<List<ProviderAdapter>> adaptersProvider) {
        com.lightai.runtime.capacity.InMemoryCapacityStore capacityStore =
                new com.lightai.runtime.capacity.InMemoryCapacityStore();
        com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort =
                snapshotPortProvider.getIfAvailable(com.lightai.runtime.ports.ConfigSnapshotPort::empty);
        com.lightai.runtime.ports.CredentialSecretPort credentialPort =
                credentialPortProvider.getIfAvailable(() -> (channelId, failoverIndex) -> {
                    throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                            "Embedded Runtime 未配置 CredentialSecretPort");
                });
        com.lightai.runtime.ports.AccessTokenPort.RuntimeConfigPort runtimeConfigPort =
                runtimeConfigPortProvider.getIfAvailable(() -> () -> java.util.Optional.empty());
        com.lightai.runtime.ports.RoutingPort routingPort = routingPortProvider.getIfAvailable(() ->
                (alias, request, estimatedInputTokens) -> new com.lightai.runtime.ports.RoutingPort.RoutingResult(
                        alias.enabledCandidates().stream()
                                .sorted(java.util.Comparator
                                        .comparingLong(com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView::priority)
                                        .thenComparingInt(candidate -> -candidate.weight()))
                                .toList(),
                        false,
                        false));
        com.lightai.runtime.ports.CapacityPort capacityPort = capacityPortProvider.getIfAvailable(() ->
                new com.lightai.runtime.capacity.StoreBackedCapacityPort(capacityStore));
        com.lightai.runtime.circuit.CircuitStateStore circuitStateStore =
                circuitStateStoreProvider.getIfAvailable(com.lightai.runtime.circuit.InMemoryCircuitStore::new);
        com.lightai.runtime.capacity.QueueService queueService = queueServiceProvider.getIfAvailable(() ->
                new com.lightai.runtime.capacity.InMemoryFifoQueue(1000, capacityStore));
        com.lightai.runtime.ports.AdapterRegistryPort adapterRegistry =
                adapterRegistryPortProvider.getIfAvailable(() -> {
                    Map<String, ProviderAdapter> adapters = new HashMap<>();
                    for (ProviderAdapter adapter : adaptersProvider.getIfAvailable(ArrayList::new)) {
                        adapters.put(adapter.providerType().toUpperCase(java.util.Locale.ROOT), adapter);
                    }
                    return providerType -> java.util.Optional.ofNullable(adapters.get(
                            providerType == null ? null : providerType.toUpperCase(java.util.Locale.ROOT)));
                });
        com.lightai.runtime.trace.TraceStore traceStore =
                traceStoreProvider.getIfAvailable(com.lightai.runtime.trace.InMemoryTraceStore::new);
        return new com.lightai.runtime.chat.ChatPipeline(
                snapshotPort,
                runtimeConfigPort,
                routingPort,
                capacityPort,
                circuitStateStore,
                credentialPort,
                queueService,
                adapterRegistry,
                traceStore,
                () -> com.lightai.runtime.chat.ReliabilityBudgets.DEFAULT,
                120_000L);
    }

    @Bean
    @ConditionalOnMissingBean(LightAiClient.class)
    public LightAiClient embeddedLightAiClient(SpringLightAiProperties properties,
                                              ObjectProvider<com.lightai.runtime.ports.ConfigSnapshotPort> snapshotPortProvider,
                                              com.lightai.runtime.chat.ChatPipeline pipeline) {
        com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort =
                snapshotPortProvider.getIfAvailable(com.lightai.runtime.ports.ConfigSnapshotPort::empty);
        return new EmbeddedPipelineLightAiClient(snapshotPort, pipeline, properties.getApplication());
    }

    public static class EmbeddedPipelineLightAiClient implements LightAiClient {
        private final com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort;
        private final com.lightai.runtime.chat.ChatPipeline chatPipeline;
        private final String application;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

        public EmbeddedPipelineLightAiClient(com.lightai.runtime.ports.ConfigSnapshotPort snapshotPort,
                                             com.lightai.runtime.chat.ChatPipeline chatPipeline,
                                             String application) {
            this.snapshotPort = snapshotPort != null ? snapshotPort : com.lightai.runtime.ports.ConfigSnapshotPort.empty();
            this.chatPipeline = chatPipeline;
            this.application = application != null ? application : "default";
        }

        private void checkOpen() {
            if (closed.get()) {
                throw new LightAiException(ErrorCode.CLIENT_CLOSED, "Java LightAiClient 已关闭，拒绝新调用");
            }
        }

        @Override
        public List<com.lightai.client.ModelInfo> models() {
            checkOpen();
            com.lightai.runtime.ports.ConfigSnapshotPort.ActiveSnapshot snapshot = snapshotPort.active();
            List<com.lightai.client.ModelInfo> result = new ArrayList<>();
            for (com.lightai.runtime.ports.ConfigSnapshotPort.AliasView alias : snapshot.aliases()) {
                if (!alias.enabled()) continue;
                List<com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView> enabledCands = alias.enabledCandidates();
                if (enabledCands.isEmpty()) continue;
                com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView first = enabledCands.get(0);
                result.add(new com.lightai.client.ModelInfo(
                        alias.alias(),
                        alias.displayName(),
                        alias.supportsStream(),
                        enabledCands.stream().anyMatch(c -> !Boolean.FALSE.equals(c.supportSystem())),
                        first.contextWindow(),
                        first.maxOutputTokens(),
                        first.temperatureMin(),
                        first.temperatureMax(),
                        first.topPMin(),
                        first.topPMax(),
                        first.maxStopSequences(),
                        null
                ));
            }
            return List.copyOf(result);
        }

        @Override
        public com.lightai.client.ChatResponse chat(com.lightai.client.ChatRequest request) {
            checkOpen();
            java.util.Objects.requireNonNull(request, "request 不能为空");
            com.lightai.runtime.ports.AccessTokenPort.Principal principal =
                    new com.lightai.runtime.ports.AccessTokenPort.Principal(application, List.of());
            com.lightai.runtime.chat.CancellationSignal cancellation = new com.lightai.runtime.chat.CancellationSignal("embedded-chat");
            com.lightai.client.chat.UnifiedChatResponse response =
                    chatPipeline.chat(new com.lightai.runtime.chat.ChatPipeline.ChatContext(principal, request.toUnified(), cancellation));
            return com.lightai.client.ChatResponse.fromUnified(response);
        }

        @Override
        public java.util.concurrent.CompletableFuture<com.lightai.client.ChatResponse> chatAsync(com.lightai.client.ChatRequest request) {
            checkOpen();
            java.util.Objects.requireNonNull(request, "request 不能为空");
            java.util.concurrent.CompletableFuture<com.lightai.client.ChatResponse> future = new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    future.complete(chat(request));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        }

        @Override
        public java.util.concurrent.Flow.Publisher<com.lightai.client.StreamEvent> stream(com.lightai.client.ChatRequest request) {
            checkOpen();
            java.util.Objects.requireNonNull(request, "request 不能为空");
            com.lightai.client.ChatRequest streamReq = request.stream() ? request : new com.lightai.client.ChatRequest(
                    request.model(), request.messages(), true, request.temperature(), request.topP(),
                    request.maxTokens(), request.stop(), request.traceId(), request.metadata(),
                    request.providerOptions(), request.streamOptions());
            com.lightai.runtime.chat.CancellationSignal cancellation = new com.lightai.runtime.chat.CancellationSignal("embedded-stream");
            com.lightai.runtime.ports.AccessTokenPort.Principal principal =
                    new com.lightai.runtime.ports.AccessTokenPort.Principal(application, List.of());
            com.lightai.runtime.chat.ChatPipeline.ChatContext context =
                    new com.lightai.runtime.chat.ChatPipeline.ChatContext(principal, streamReq.toUnified(), cancellation);
            com.lightai.client.internal.FlowStreamPublisher publisher =
                    new com.lightai.client.internal.FlowStreamPublisher(() -> cancellation.cancel("client-cancelled"));

            java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicBoolean doneEmitted = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicReference<String> lastTraceId = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> lastModel = new java.util.concurrent.atomic.AtomicReference<>(streamReq.model());
            java.util.concurrent.atomic.AtomicReference<String> lastProvider = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> lastProviderModel = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicLong lastSequence = new java.util.concurrent.atomic.AtomicLong(0);

            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    chatPipeline.chatStream(context, new com.lightai.runtime.chat.ChatPipeline.StreamListener() {
                        @Override
                        public void onCommit() {}

                        @Override
                        public void onChunk(com.lightai.client.chat.UnifiedChatChunk chunk) {
                            if (chunk.id() != null) lastTraceId.set(chunk.id());
                            if (chunk.model() != null) lastModel.set(chunk.model());
                            if (chunk.lightAi() != null) {
                                if (chunk.lightAi().traceId() != null) lastTraceId.set(chunk.lightAi().traceId());
                                if (chunk.lightAi().provider() != null) lastProvider.set(chunk.lightAi().provider());
                                if (chunk.lightAi().providerModel() != null) lastProviderModel.set(chunk.lightAi().providerModel());
                                lastSequence.set(Math.max(lastSequence.get(), chunk.lightAi().sequence()));
                            }
                            if (started.compareAndSet(false, true)) {
                                publisher.submit(com.lightai.client.StreamEvent.start(lastTraceId.get(), lastModel.get(),
                                        lastProvider.get(), lastProviderModel.get()));
                            }
                            if (chunk.choices() != null) {
                                for (com.lightai.client.chat.UnifiedChatChunk.ChunkChoice choice : chunk.choices()) {
                                    if (choice.delta() != null && choice.delta().content() != null && !choice.delta().content().isEmpty()) {
                                        publisher.submit(com.lightai.client.StreamEvent.delta(lastTraceId.get(), lastSequence.get(),
                                                lastModel.get(), lastProvider.get(), lastProviderModel.get(), choice.delta().content()));
                                    }
                                    if (choice.finishReason() != null) {
                                        publisher.submit(com.lightai.client.StreamEvent.done(lastTraceId.get(), lastSequence.get(),
                                                lastModel.get(), lastProvider.get(), lastProviderModel.get(), choice.finishReason(), null));
                                        doneEmitted.set(true);
                                    }
                                }
                            }
                            if (chunk.usage() != null) {
                                publisher.submit(com.lightai.client.StreamEvent.usage(lastTraceId.get(), lastSequence.get(),
                                        lastModel.get(), lastProvider.get(), lastProviderModel.get(), chunk.usage(),
                                        chunk.lightAi() != null ? chunk.lightAi().cost() : null));
                            }
                        }

                        @Override
                        public void onError(com.lightai.client.error.UnifiedError error) {
                            publisher.error(new LightAiException(ErrorCode.valueOf(error.code()), error.message()));
                        }

                        @Override
                        public void onComplete() {
                            if (cancellation.cancelled()) return;
                            if (doneEmitted.compareAndSet(false, true)) {
                                publisher.submit(com.lightai.client.StreamEvent.done(lastTraceId.get(), lastSequence.get() + 1,
                                        lastModel.get(), lastProvider.get(), lastProviderModel.get(), "stop", null));
                            }
                            publisher.complete();
                        }
                    });
                } catch (LightAiException e) {
                    if (!cancellation.cancelled()) {
                        publisher.error(e);
                    }
                } catch (Throwable t) {
                    if (!cancellation.cancelled()) {
                        publisher.error(new LightAiException(ErrorCode.INTERNAL_ERROR, t.getMessage()));
                    }
                }
            });

            return publisher;
        }

        @Override
        public void close() {
            closed.set(true);
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }
    }

    /**
     * Embedded Admin UI 安全与本地访问过滤器（PRD 4.6.3.4，RV-047）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(FilterRegistrationBean.class)
    @ConditionalOnProperty(prefix = "light-ai.admin", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class EmbeddedAdminSecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "embeddedAdminSecurityFilterRegistration")
        public FilterRegistrationBean<EmbeddedAdminSecurityFilter> embeddedAdminSecurityFilterRegistration(
                SpringLightAiProperties properties,
                ObjectProvider<AuthContextProvider> authContextProvider) {

            EmbeddedAdminSecurityFilter filter = new EmbeddedAdminSecurityFilter(
                    properties,
                    authContextProvider.getIfAvailable()
            );

            FilterRegistrationBean<EmbeddedAdminSecurityFilter> registration = new FilterRegistrationBean<>(filter);
            String path = properties.getAdmin().getPath();
            if (!path.endsWith("/*")) {
                path = path.endsWith("/") ? path + "*" : path + "/*";
            }
            registration.addUrlPatterns(path);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
            return registration;
        }
    }

    public static class EmbeddedAdminSecurityFilter extends OncePerRequestFilter {

        private final SpringLightAiProperties properties;
        private final AuthContextProvider authContextProvider;

        public EmbeddedAdminSecurityFilter(SpringLightAiProperties properties,
                                           AuthContextProvider authContextProvider) {
            this.properties = properties;
            this.authContextProvider = authContextProvider;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {

            AuthContext authContext = resolveContext(request);
            if (authContext == null || !authContext.authenticated()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":{\"code\":\"ACCESS_DENIED\",\"message\":\"未授权访问 Embedded Admin\"}}");
                return;
            }

            filterChain.doFilter(request, response);
        }

        private AuthContext resolveContext(HttpServletRequest request) {
            if (authContextProvider != null) {
                try {
                    Map<String, String> headers = new HashMap<>();
                    Enumeration<String> headerNames = request.getHeaderNames();
                    if (headerNames != null) {
                        while (headerNames.hasMoreElements()) {
                            String name = headerNames.nextElement();
                            headers.put(name, request.getHeader(name));
                        }
                    }
                    return authContextProvider.resolve(new AuthRequest(
                            request.getMethod(),
                            request.getRequestURI(),
                            headers,
                            request.getRemoteAddr()
                    ));
                } catch (Exception e) {
                    return AuthContext.anonymous();
                }
            }

            // 无 AuthContextProvider 时，检查 localAccessEnabled
            if (properties.getAdmin().isLocalAccessEnabled()) {
                String remoteAddr = request.getRemoteAddr();
                if (isLoopbackOrTrusted(remoteAddr, properties.getAdmin().getTrustedNetworkCidrs())) {
                    return AuthContext.authenticated(
                            "LOCAL_ADMIN",
                            "本地管理员",
                            Set.of("ADMIN", "OPERATOR"),
                            List.of(properties.getApplication() != null ? properties.getApplication() : "*")
                    );
                }
            }

            return AuthContext.anonymous();
        }

        private static boolean isLoopbackOrTrusted(String remoteAddr, List<String> trustedCidrs) {
            if (remoteAddr == null || remoteAddr.isBlank()) return false;
            String addr = remoteAddr.strip();
            if (addr.startsWith("[") && addr.endsWith("]")) {
                addr = addr.substring(1, addr.length() - 1);
            }
            try {
                java.net.InetAddress target = java.net.InetAddress.getByName(addr);
                if (target.isLoopbackAddress() || target.isAnyLocalAddress()) {
                    return true;
                }
                if (trustedCidrs == null || trustedCidrs.isEmpty()) {
                    return false;
                }
                byte[] actual = target.getAddress();
                for (String raw : trustedCidrs) {
                    if (raw == null || raw.isBlank()) continue;
                    String value = raw.strip();
                    int slash = value.indexOf('/');
                    byte[] expected = java.net.InetAddress.getByName(slash < 0 ? value : value.substring(0, slash)).getAddress();
                    if (expected.length != actual.length) continue;
                    int prefix = slash < 0 ? expected.length * 8 : Integer.parseInt(value.substring(slash + 1));
                    if (prefix < 0 || prefix > expected.length * 8) continue;
                    int fullBytes = prefix / 8;
                    int remaining = prefix % 8;
                    boolean match = true;
                    for (int i = 0; i < fullBytes; i++) {
                        if (actual[i] != expected[i]) { match = false; break; }
                    }
                    if (match && remaining > 0) {
                        int mask = 0xff << (8 - remaining);
                        match = (actual[fullBytes] & mask) == (expected[fullBytes] & mask);
                    }
                    if (match) return true;
                }
            } catch (Exception ignored) {
            }
            return false;
        }
    }
}
