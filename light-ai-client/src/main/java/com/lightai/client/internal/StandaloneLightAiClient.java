package com.lightai.client.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightai.client.ChatRequest;
import com.lightai.client.ChatResponse;
import com.lightai.client.LightAiClient;
import com.lightai.client.ModelInfo;
import com.lightai.client.StreamEvent;
import com.lightai.client.StreamEventType;
import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.client.chat.UnifiedModelList;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.error.UnifiedErrorEnvelope;
import com.lightai.client.json.ProtocolJson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * STANDALONE_CLIENT 远程 HTTP 客户端实现（BE-051，4.6.2.4）：
 * 1. build 时不探测网络；
 * 2. 传输复用 JDK HttpClient 连接池；
 * 3. 动态从 tokenSupplier 获取 Bearer Token；
 * 4. 未知响应字段忽略；
 * 5. 传输失败（仅在未写请求体时）有限重试；
 * 6. 无法解析的响应转换为 SERVER_PROTOCOL_ERROR。
 */
public class StandaloneLightAiClient implements LightAiClient {

    private static final ObjectMapper CLIENT_MAPPER = ProtocolJson.protocol().copy()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private final String baseUrl;
    private final Supplier<String> tokenSupplier;
    private final HttpClient httpClient;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int transportRetryCount;
    private final long closeTimeoutMs;
    private final boolean customHttpClient;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public StandaloneLightAiClient(String baseUrl,
                                   Supplier<String> tokenSupplier,
                                   HttpClient httpClient,
                                   Duration connectTimeout,
                                   Duration readTimeout,
                                   int transportRetryCount,
                                   long closeTimeoutMs) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier 不能为空");
        this.connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(10);
        this.readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(60);
        this.transportRetryCount = Math.max(0, transportRetryCount);
        this.closeTimeoutMs = closeTimeoutMs > 0 ? closeTimeoutMs : 5000L;

        if (httpClient != null) {
            this.httpClient = httpClient;
            this.customHttpClient = true;
        } else {
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(this.connectTimeout)
                    .build();
            this.customHttpClient = false;
        }
    }

    private static String normalizeBaseUrl(String url) {
        Objects.requireNonNull(url, "baseUrl 不能为空");
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "baseUrl 必须以 http:// 或 https:// 开头", "base_url");
        }
        return trimmed;
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new LightAiException(ErrorCode.CLIENT_CLOSED, "Java LightAiClient 已关闭，拒绝新调用");
        }
    }

    private String getAuthorizationHeader() {
        String token = tokenSupplier.get();
        if (token == null || token.isBlank()) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "tokenSupplier 返回空令牌");
        }
        return token.startsWith("Bearer ") ? token : ("Bearer " + token);
    }

    @Override
    public List<ModelInfo> models() {
        checkOpen();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/models"))
                .header("Authorization", getAuthorizationHeader())
                .header("Accept", "application/json")
                .timeout(readTimeout)
                .GET()
                .build();

        HttpResponse<String> response = sendWithRetry(request);
        if (response.statusCode() == 200) {
            try {
                UnifiedModelList list = CLIENT_MAPPER.readValue(response.body(), UnifiedModelList.class);
                List<ModelInfo> result = new ArrayList<>();
                if (list != null && list.data() != null) {
                    for (UnifiedModelList.ModelSummary s : list.data()) {
                        UnifiedModelList.LightAiModelInfo info = s.lightAi();
                        result.add(new ModelInfo(
                                s.id(),
                                info != null ? info.displayName() : s.id(),
                                info != null && Boolean.TRUE.equals(info.supportStream()),
                                info == null || !Boolean.FALSE.equals(info.supportSystem()),
                                info != null ? info.contextWindow() : null,
                                info != null ? info.maxOutputTokens() : null,
                                info != null ? info.temperatureMin() : null,
                                info != null ? info.temperatureMax() : null,
                                info != null ? info.topPMin() : null,
                                info != null ? info.topPMax() : null,
                                info != null ? info.stopMax() : null,
                                info != null ? info.updatedAt() : null
                        ));
                    }
                }
                return List.copyOf(result);
            } catch (Exception e) {
                throw new LightAiException(ErrorCode.SERVER_PROTOCOL_ERROR, "解析 /v1/models 响应失败: " + safeSummary(response.body()));
            }
        }

        handleHttpError(response.statusCode(), response.body());
        return List.of();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        checkOpen();
        Objects.requireNonNull(request, "request 不能为空");
        UnifiedChatRequest unifiedRequest = request.toUnified();

        String bodyJson;
        try {
            bodyJson = ProtocolJson.protocol().writeValueAsString(unifiedRequest);
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "请求序列化失败: " + e.getMessage());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", getAuthorizationHeader())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .timeout(readTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));

        if (unifiedRequest.traceId() != null && !unifiedRequest.traceId().isBlank()) {
            builder.header("X-Trace-Id", unifiedRequest.traceId());
        }

        HttpResponse<String> response = sendWithRetry(builder.build());
        if (response.statusCode() == 200) {
            try {
                UnifiedChatResponse unifiedResponse = CLIENT_MAPPER.readValue(response.body(), UnifiedChatResponse.class);
                return ChatResponse.fromUnified(unifiedResponse);
            } catch (Exception e) {
                throw new LightAiException(ErrorCode.SERVER_PROTOCOL_ERROR, "解析 ChatResponse 失败: " + safeSummary(response.body()));
            }
        }

        handleHttpError(response.statusCode(), response.body());
        return null;
    }

    @Override
    public CompletableFuture<ChatResponse> chatAsync(ChatRequest request) {
        checkOpen();
        Objects.requireNonNull(request, "request 不能为空");
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                ChatResponse resp = chat(request);
                future.complete(resp);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        return future;
    }

    @Override
    public Flow.Publisher<StreamEvent> stream(ChatRequest request) {
        checkOpen();
        Objects.requireNonNull(request, "request 不能为空");
        ChatRequest streamReq = request.stream() ? request : new ChatRequest(
                request.model(), request.messages(), true, request.temperature(), request.topP(),
                request.maxTokens(), request.stop(), request.traceId(), request.metadata(),
                request.providerOptions(), request.streamOptions());

        String bodyJson;
        try {
            bodyJson = ProtocolJson.protocol().writeValueAsString(streamReq.toUnified());
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "流请求序列化失败: " + e.getMessage());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", getAuthorizationHeader())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/event-stream")
                .timeout(readTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));

        if (streamReq.traceId() != null && !streamReq.traceId().isBlank()) {
            builder.header("X-Trace-Id", streamReq.traceId());
        }

        HttpRequest httpRequest = builder.build();
        AtomicBoolean streamCancelled = new AtomicBoolean(false);
        FlowStreamPublisher publisher = new FlowStreamPublisher(() -> streamCancelled.set(true));
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean doneEmitted = new AtomicBoolean(false);

        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    byte[] errBytes = response.body().readAllBytes();
                    String errBody = new String(errBytes, StandardCharsets.UTF_8);
                    try {
                        handleHttpError(response.statusCode(), errBody);
                    } catch (LightAiException ex) {
                        publisher.error(ex);
                        return;
                    }
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    String lastTraceId = null;
                    String lastModel = request.model();
                    String lastProvider = null;
                    String lastProviderModel = null;
                    long lastSequence = 0;

                    while (!streamCancelled.get() && (line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith(":")) {
                            continue;
                        }
                        if (line.startsWith("data:")) {
                            String data = line.substring("data:".length()).trim();
                            if ("[DONE]".equals(data)) {
                                if (!doneEmitted.get() && started.get()) {
                                    publisher.submit(StreamEvent.done(lastTraceId, lastSequence + 1, lastModel, lastProvider, lastProviderModel, "stop", null));
                                    doneEmitted.set(true);
                                }
                                break;
                            }
                            // 解析 JSON chunk 或 error
                            try {
                                JsonNode node = CLIENT_MAPPER.readTree(data);
                                if (node.has("error")) {
                                    JsonNode errNode = node.get("error");
                                    String codeStr = errNode.has("code") ? errNode.get("code").asText() : "INTERNAL_ERROR";
                                    String msg = errNode.has("message") ? errNode.get("message").asText() : "服务端错误";
                                    ErrorCode errCode = parseErrorCode(codeStr, ErrorCode.INTERNAL_ERROR);
                                    publisher.error(new LightAiException(errCode, msg));
                                    return;
                                }

                                UnifiedChatChunk chunk = CLIENT_MAPPER.treeToValue(node, UnifiedChatChunk.class);
                                if (chunk != null) {
                                    if (chunk.id() != null) lastTraceId = chunk.id();
                                    if (chunk.model() != null) lastModel = chunk.model();
                                    if (chunk.lightAi() != null) {
                                        if (chunk.lightAi().provider() != null) lastProvider = chunk.lightAi().provider();
                                        if (chunk.lightAi().providerModel() != null) lastProviderModel = chunk.lightAi().providerModel();
                                        lastSequence = chunk.lightAi().sequence();
                                    }

                                    dispatchChunk(publisher, chunk, started, doneEmitted);
                                }
                            } catch (Exception parseEx) {
                                publisher.error(new LightAiException(ErrorCode.SERVER_PROTOCOL_ERROR, "解析 SSE 数据块失败: " + safeSummary(data)));
                                return;
                            }
                        }
                    }
                    if (!streamCancelled.get()) {
                        publisher.complete();
                    }
                }
            } catch (Throwable t) {
                if (!streamCancelled.get()) {
                    publisher.error(new LightAiException(ErrorCode.SERVER_PROTOCOL_ERROR, "流读取失败: " + t.getMessage()));
                }
            }
        });

        return publisher;
    }

    private static void dispatchChunk(FlowStreamPublisher publisher, UnifiedChatChunk chunk,
                                      AtomicBoolean started, AtomicBoolean doneEmitted) {
        String traceId = chunk.id();
        String model = chunk.model();
        String provider = chunk.lightAi() != null ? chunk.lightAi().provider() : null;
        String providerModel = chunk.lightAi() != null ? chunk.lightAi().providerModel() : null;
        long sequence = chunk.lightAi() != null ? chunk.lightAi().sequence() : 0L;

        if (started.compareAndSet(false, true)) {
            publisher.submit(StreamEvent.start(traceId, model, provider, providerModel));
        }

        if (chunk.choices() != null) {
            for (UnifiedChatChunk.ChunkChoice choice : chunk.choices()) {
                if (choice.delta() != null && choice.delta().content() != null) {
                    publisher.submit(StreamEvent.delta(traceId, sequence, model, provider, providerModel, choice.delta().content()));
                }
                if (choice.finishReason() != null) {
                    publisher.submit(StreamEvent.done(traceId, sequence, model, provider, providerModel, choice.finishReason(), null));
                    doneEmitted.set(true);
                }
            }
        }

        if (chunk.usage() != null) {
            publisher.submit(StreamEvent.usage(traceId, sequence, model, provider, providerModel, chunk.usage(),
                    chunk.lightAi() != null ? chunk.lightAi().cost() : null));
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (ConnectException e) {
                if (attempts <= transportRetryCount) {
                    continue;
                }
                throw new LightAiException(ErrorCode.SERVER_PROTOCOL_ERROR, "连接服务端失败: " + e.getMessage());
            } catch (HttpTimeoutException e) {
                throw new LightAiException(ErrorCode.TOTAL_TIMEOUT, "请求超时: " + e.getMessage());
            } catch (IOException e) {
                if (attempts <= transportRetryCount && isSafeToRetry(e)) {
                    continue;
                }
                throw new LightAiException(ErrorCode.SERVER_PROTOCOL_ERROR, "HTTP 通信异常: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LightAiException(ErrorCode.CLIENT_CANCELLED, "请求线程被中断");
            }
        }
    }

    private static boolean isSafeToRetry(IOException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("connection reset") || msg.contains("connection refused");
    }

    private void handleHttpError(int statusCode, String body) {
        if (body != null && !body.isBlank()) {
            try {
                UnifiedErrorEnvelope envelope = ProtocolJson.protocol().readValue(body, UnifiedErrorEnvelope.class);
                if (envelope != null && envelope.error() != null) {
                    ErrorCode errCode = parseErrorCode(envelope.error().code(), ErrorCode.SERVER_PROTOCOL_ERROR);
                    throw new LightAiException(errCode, envelope.error().message(), envelope.error().param());
                }
            } catch (LightAiException e) {
                throw e;
            } catch (Exception ignored) {
            }
        }
        throw new LightAiException(ErrorCode.SERVER_PROTOCOL_ERROR, "HTTP 状态码 " + statusCode + ", 响应: " + safeSummary(body));
    }

    private static ErrorCode parseErrorCode(String codeStr, ErrorCode defaultCode) {
        if (codeStr != null) {
            try {
                return ErrorCode.valueOf(codeStr);
            } catch (Exception ignored) {
            }
        }
        return defaultCode;
    }

    private static String safeSummary(String body) {
        if (body == null) return "";
        String trimmed = body.replaceAll("\\s+", " ").trim();
        if (trimmed.length() > 1000) {
            return trimmed.substring(0, 1000) + "...";
        }
        return trimmed;
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