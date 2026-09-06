package com.lightai.runtime.local;

import com.lightai.client.ChatRequest;
import com.lightai.client.ChatResponse;
import com.lightai.client.LightAiClient;
import com.lightai.client.ModelInfo;
import com.lightai.client.StreamEvent;
import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.error.UnifiedError;
import com.lightai.client.internal.FlowStreamPublisher;
import com.lightai.runtime.chat.CancellationSignal;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.export.TraceExportCoordinator;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.secret.SecretManager;
import com.lightai.spi.export.ExportedTrace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LOCAL_RUNTIME 本地运行客户端实现（BE-050，4.6.2.3）：
 * 1. 离线内存快照 snapshot_no=1；
 * 2. 纯内存 ChatPipeline 调用；
 * 3. 异步支持 Future 取消与 Flow 背压（BE-052）；
 * 4. Secret SPI 冲突与缓存（BE-053）；
 * 5. TraceExporter 异步隔离（BE-054）。
 */
public class LocalLightAiClient implements LightAiClient {

    private final ConfigSnapshotPort.ActiveSnapshot snapshot;
    private final ChatPipeline chatPipeline;
    private final SecretManager secretManager;
    private final TraceExportCoordinator exportCoordinator;
    private final long closeTimeoutMs;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LocalLightAiClient(ConfigSnapshotPort.ActiveSnapshot snapshot,
                              ChatPipeline chatPipeline,
                              SecretManager secretManager,
                              TraceExportCoordinator exportCoordinator,
                              long closeTimeoutMs) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot 不能为空");
        this.chatPipeline = Objects.requireNonNull(chatPipeline, "chatPipeline 不能为空");
        this.secretManager = secretManager;
        this.exportCoordinator = exportCoordinator;
        this.closeTimeoutMs = closeTimeoutMs > 0 ? closeTimeoutMs : 5000L;
    }

    private void checkOpen() {
        if (closed.get()) {
            throw new LightAiException(ErrorCode.CLIENT_CLOSED, "Java LightAiClient 已关闭，拒绝新调用");
        }
    }

    @Override
    public List<ModelInfo> models() {
        checkOpen();
        List<ModelInfo> result = new ArrayList<>();
        for (ConfigSnapshotPort.AliasView alias : snapshot.aliases()) {
            if (!alias.enabled()) {
                continue;
            }
            List<ConfigSnapshotPort.CandidateView> enabledCands = alias.enabledCandidates();
            if (enabledCands.isEmpty()) {
                continue;
            }
            ConfigSnapshotPort.CandidateView first = enabledCands.get(0);
            result.add(new ModelInfo(
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
    public ChatResponse chat(ChatRequest request) {
        checkOpen();
        Objects.requireNonNull(request, "request 不能为空");
        UnifiedChatRequest unifiedRequest = request.toUnified();

        CancellationSignal cancellation = new CancellationSignal("local-chat");
        AccessTokenPort.Principal principal = new AccessTokenPort.Principal("LOCAL_RUNTIME", List.of());
        ChatPipeline.ChatContext context = new ChatPipeline.ChatContext(principal, unifiedRequest, cancellation);

        long started = System.currentTimeMillis();
        UnifiedChatResponse unifiedResponse = chatPipeline.chat(context);
        long duration = System.currentTimeMillis() - started;

        // 异步提交 Trace 导出（BE-054）
        if (exportCoordinator != null && exportCoordinator.hasExporters()) {
            ExportedTrace trace = toExportedTrace(unifiedResponse, duration);
            exportCoordinator.submit(trace);
        }

        return ChatResponse.fromUnified(unifiedResponse);
    }

    @Override
    public CompletableFuture<ChatResponse> chatAsync(ChatRequest request) {
        checkOpen();
        Objects.requireNonNull(request, "request 不能为空");
        UnifiedChatRequest unifiedRequest = request.toUnified();

        CancellationSignal cancellation = new CancellationSignal("local-async");
        AccessTokenPort.Principal principal = new AccessTokenPort.Principal("LOCAL_RUNTIME", List.of());
        ChatPipeline.ChatContext context = new ChatPipeline.ChatContext(principal, unifiedRequest, cancellation);

        CompletableFuture<ChatResponse> future = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                long started = System.currentTimeMillis();
                UnifiedChatResponse unifiedResponse = chatPipeline.chat(context);
                long duration = System.currentTimeMillis() - started;

                if (exportCoordinator != null && exportCoordinator.hasExporters()) {
                    ExportedTrace trace = toExportedTrace(unifiedResponse, duration);
                    exportCoordinator.submit(trace);
                }

                future.complete(ChatResponse.fromUnified(unifiedResponse));
            } catch (LightAiException e) {
                if (cancellation.cancelled()) {
                    future.completeExceptionally(new CancellationException("调用已取消"));
                } else {
                    future.completeExceptionally(e);
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        // 绑定取消处理（BE-052）
        future.whenComplete((res, err) -> {
            if (future.isCancelled()) {
                cancellation.cancel("client-cancelled");
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

        UnifiedChatRequest unifiedRequest = streamReq.toUnified();
        CancellationSignal cancellation = new CancellationSignal("local-stream");
        AccessTokenPort.Principal principal = new AccessTokenPort.Principal("LOCAL_RUNTIME", List.of());
        ChatPipeline.ChatContext context = new ChatPipeline.ChatContext(principal, unifiedRequest, cancellation);

        FlowStreamPublisher publisher = new FlowStreamPublisher(() -> cancellation.cancel("client-cancelled"));
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean doneEmitted = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> lastTraceId = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> lastModel = new java.util.concurrent.atomic.AtomicReference<>(streamReq.model());
        java.util.concurrent.atomic.AtomicReference<String> lastProvider = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> lastProviderModel = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong lastSequence = new java.util.concurrent.atomic.AtomicLong(0);

        CompletableFuture.runAsync(() -> {
            try {
                chatPipeline.chatStream(context, new ChatPipeline.StreamListener() {
                    @Override
                    public void onCommit() {
                    }

                    @Override
                    public void onChunk(UnifiedChatChunk chunk) {
                        if (chunk.id() != null) lastTraceId.set(chunk.id());
                        if (chunk.model() != null) lastModel.set(chunk.model());
                        if (chunk.lightAi() != null) {
                            if (chunk.lightAi().provider() != null) lastProvider.set(chunk.lightAi().provider());
                            if (chunk.lightAi().providerModel() != null) lastProviderModel.set(chunk.lightAi().providerModel());
                            lastSequence.set(chunk.lightAi().sequence());
                        }
                        dispatchChunk(publisher, chunk, started, doneEmitted);
                    }

                    @Override
                    public void onError(UnifiedError error) {
                        publisher.error(new LightAiException(ErrorCode.valueOf(error.code()), error.message()));
                    }
                });

                if (!cancellation.cancelled()) {
                    if (!doneEmitted.get() && started.get()) {
                        publisher.submit(StreamEvent.done(lastTraceId.get(), lastSequence.get() + 1, lastModel.get(), lastProvider.get(), lastProviderModel.get(), "stop", null));
                        doneEmitted.set(true);
                    }
                    publisher.complete();
                }
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

    private static ExportedTrace toExportedTrace(UnifiedChatResponse resp, long duration) {
        String provider = resp.lightAi() != null ? resp.lightAi().provider() : "local";
        String providerModel = resp.lightAi() != null ? resp.lightAi().providerModel() : resp.model();
        Long pt = resp.usage() != null ? resp.usage().promptTokens() : null;
        Long ct = resp.usage() != null ? resp.usage().completionTokens() : null;
        Long tt = resp.usage() != null ? resp.usage().totalTokens() : null;
        java.math.BigDecimal costAmt = (resp.lightAi() != null && resp.lightAi().cost() != null)
                ? resp.lightAi().cost().amount() : null;
        String costCur = (resp.lightAi() != null && resp.lightAi().cost() != null)
                ? resp.lightAi().cost().currency() : "USD";

        return new ExportedTrace(
                resp.id(), resp.model(), provider, providerModel,
                "SUCCEEDED", duration, Instant.now().minusMillis(duration), Instant.now(),
                pt, ct, tt, costAmt, costCur, null, null
        );
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (secretManager != null) {
                secretManager.clear();
            }
            if (exportCoordinator != null) {
                exportCoordinator.close();
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }
}