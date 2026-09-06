package com.lightai.client;

import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.internal.StandaloneLightAiClient;

import java.io.Closeable;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * 轻享 AI Java SDK 统一客户端（BE-049，4.6.2.2）：
 * 支持 STANDALONE_CLIENT 与 LOCAL_RUNTIME 两大模式。
 * 客户端线程安全，应作为应用单例复用。
 * 关闭后拒绝新调用并抛出 CLIENT_CLOSED。
 */
public interface LightAiClient extends Closeable {

    static Builder builder() {
        return new Builder();
    }

    /**
     * 查询可用模型/别名列表。
     */
    List<ModelInfo> models();

    /**
     * 同步非流式 Chat 调用。
     */
    ChatResponse chat(ChatRequest request);

    default ChatResponse chat(UnifiedChatRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        return chat(ChatRequest.fromUnified(request));
    }

    /**
     * 异步非流式 Chat 调用。
     */
    CompletableFuture<ChatResponse> chatAsync(ChatRequest request);

    default CompletableFuture<ChatResponse> chatAsync(UnifiedChatRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        return chatAsync(ChatRequest.fromUnified(request));
    }

    /**
     * 流式响应式发布者（Java Flow.Publisher）。
     */
    Flow.Publisher<StreamEvent> stream(ChatRequest request);

    default Flow.Publisher<StreamEvent> stream(UnifiedChatRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        return stream(ChatRequest.fromUnified(request));
    }

    /**
     * 关闭客户端并释放资源；关闭后调用拒绝新请求（CLIENT_CLOSED）。
     */
    @Override
    void close();

    /**
     * 检查客户端是否已关闭。
     */
    boolean isClosed();

    /**
     * 客户端构建器（4.6.2.2）。
     */
    class Builder {
        private String baseUrl;
        private Supplier<String> tokenSupplier;
        private HttpClient httpClient;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(60);
        private int transportRetryCount = 0;
        private long closeTimeoutMs = 5000L;

        // LOCAL_RUNTIME 配置
        private Object localRuntimeDefinition;
        private Map<String, Supplier<char[]>> credentialSecretSuppliers;
        private List<?> adapters;
        private List<?> secretProviders;
        private List<?> traceExporters;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder tokenSupplier(Supplier<String> tokenSupplier) {
            this.tokenSupplier = tokenSupplier;
            return this;
        }

        public Builder token(String token) {
            this.tokenSupplier = () -> token;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        public Builder transportRetryCount(int transportRetryCount) {
            this.transportRetryCount = transportRetryCount;
            return this;
        }

        public Builder closeTimeoutMs(long closeTimeoutMs) {
            this.closeTimeoutMs = closeTimeoutMs;
            return this;
        }

        public Builder localRuntimeDefinition(Object localRuntimeDefinition) {
            this.localRuntimeDefinition = localRuntimeDefinition;
            return this;
        }

        public Builder credentialSecretSuppliers(Map<String, Supplier<char[]>> suppliers) {
            this.credentialSecretSuppliers = suppliers;
            return this;
        }

        public Builder adapters(List<?> adapters) {
            this.adapters = adapters;
            return this;
        }

        public Builder secretProviders(List<?> secretProviders) {
            this.secretProviders = secretProviders;
            return this;
        }

        public Builder traceExporters(List<?> traceExporters) {
            this.traceExporters = traceExporters;
            return this;
        }

        public LightAiClient build() {
            if (localRuntimeDefinition != null) {
                return buildLocalRuntimeClient();
            }

            if (baseUrl != null && !baseUrl.isBlank()) {
                if (tokenSupplier == null) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "STANDALONE_CLIENT 模式必须提供 tokenSupplier", "token_supplier");
                }
                return new StandaloneLightAiClient(
                        baseUrl,
                        tokenSupplier,
                        httpClient,
                        connectTimeout,
                        readTimeout,
                        transportRetryCount,
                        closeTimeoutMs
                );
            }

            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "必须指定 baseUrl (STANDALONE_CLIENT) 或 localRuntimeDefinition (LOCAL_RUNTIME)");
        }

        private LightAiClient buildLocalRuntimeClient() {
            try {
                Class<?> factoryClass = Class.forName("com.lightai.runtime.local.LocalLightAiClientFactory");
                Method method = factoryClass.getMethod("create", Object.class, Map.class, List.class, List.class, List.class, long.class);
                return (LightAiClient) method.invoke(null, localRuntimeDefinition, credentialSecretSuppliers, adapters, secretProviders, traceExporters, closeTimeoutMs);
            } catch (ClassNotFoundException e) {
                throw new LightAiException(ErrorCode.MODE_NOT_SUPPORTED, "未引入 light-ai-runtime 依赖，无法启动 LOCAL_RUNTIME 模式");
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof LightAiException lae) {
                    throw lae;
                }
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "构建 LOCAL_RUNTIME 客户端失败: " + (cause != null ? cause.getMessage() : e.getMessage()));
            } catch (Exception e) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "构建 LOCAL_RUNTIME 客户端反射异常: " + e.getMessage());
            }
        }
    }
}