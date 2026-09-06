package com.lightai.runtime;

import com.lightai.client.chat.ChatMessage;
import com.lightai.client.chat.StreamOptions;
import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.error.UnifiedError;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.CancellationSignal;
import com.lightai.runtime.chat.ReliabilityBudgets;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.AdapterRegistryPort;
import com.lightai.runtime.ports.CapacityPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.runtime.ports.RoutingPort;
import com.lightai.runtime.trace.InMemoryTraceStore;
import com.lightai.runtime.trace.TraceStore;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderModelDescriptor;
import com.lightai.spi.provider.ProviderStreamChunk;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一调用管线语义（BE-027/028/029）：Alias 前后失败、Trace 最终化、
 * 恢复预算不越界、流式提交边界、取消一次释放。
 */
class ChatPipelineTest {

    private static final CandidateView CANDIDATE_A = candidate("cand-a", "OPENAI", "model-a", 10, "0.00000015",
            "0.00000060");
    private static final CandidateView CANDIDATE_B = candidate("cand-b", "DEEPSEEK", "model-b", 20, "0.00000010",
            "0.00000030");

    private InMemoryTraceStore traceStore;
    private RecordingCapacity capacity;
    private StubAdapter adapter;
    private ChatPipeline pipeline;

    @BeforeEach
    void setUp() {
        traceStore = new InMemoryTraceStore();
        capacity = new RecordingCapacity();
        adapter = new StubAdapter();
        AdapterRegistryPort registry = type ->
                Optional.ofNullable("OPENAI".equals(type) || "DEEPSEEK".equals(type) ? adapter : null);
        CredentialSecretPort credentials = (poolId, failoverIndex) ->
                new CredentialSecretPort.ResolvedCredential(poolId + "-c" + failoverIndex,
                        () -> "sk-test".toCharArray());
        pipeline = new ChatPipeline(snapshot(), () -> Optional.<String>empty(), routing(),
                capacity, credentials, registry, traceStore, () -> ReliabilityBudgets.DEFAULT, 30_000);
    }

    @Test
    void syncHappyPathFinalizesTraceAndAttachesCost() {
        adapter.response = new ProviderChatResponse("你好", "stop", 100L, 20L, 120L, "ACTUAL", "req-1");
        UnifiedChatResponse response = pipeline.chat(context(request("assistant", false), null));

        assertThat(response.id()).isNotBlank();
        assertThat(response.object()).isEqualTo("chat.completion");
        assertThat(response.model()).isEqualTo("assistant");
        assertThat(response.choices().get(0).message().content()).isEqualTo("你好");
        assertThat(response.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(response.usage().promptTokens()).isEqualTo(100L);
        assertThat(response.usage().source()).isEqualTo("ACTUAL");
        assertThat(response.lightAi().provider()).isEqualTo("OPENAI");
        assertThat(response.lightAi().providerModel()).isEqualTo("model-a");
        assertThat(response.lightAi().cost().currency()).isEqualTo("USD");
        assertThat(response.lightAi().traceId()).isEqualTo(response.id());
        assertThat(traceStore.statusOf(response.id())).isEqualTo("SUCCEEDED");
        assertThat(traceStore.committed(response.id())).isFalse();
        assertThat(capacity.settled).hasSize(1);
        assertThat(capacity.released).isEmpty();
    }

    @Test
    void aliasFailureBeforeTraceCreatesNoTrace() {
        assertThatThrownBy(() -> pipeline.chat(context(request("no-such-alias", false), null)))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MODEL_ALIAS_NOT_FOUND));
    }

    @Test
    void clientTraceIdConflictRejected() {
        adapter.response = new ProviderChatResponse("ok", "stop", 1L, 1L, 2L, "ACTUAL", "req-0");
        UnifiedChatRequest request = withTraceId(request("assistant", false), "fixed-trace");
        pipeline.chat(context(request, null));
        assertThatThrownBy(() -> pipeline.chat(context(request, null)))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.TRACE_ID_CONFLICT));
    }

    @Test
    void adapterFailureExhaustsBudgetThenFinalizesFailed() {
        adapter.error = ProviderFailure.http(500, null, "boom");
        UnifiedChatRequest request = withTraceId(request("assistant", false), "t-budget");
        assertThatThrownBy(() -> pipeline.chat(context(request, null)))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ALL_CANDIDATES_FAILED));
        // 预算 = 1 + 1 retry + 1 failover + 1 fallback = 4 次外部尝试
        assertThat(adapter.invocations.get()).isEqualTo(4);
        assertThat(traceStore.statusOf("t-budget")).isEqualTo("FAILED");
        // 每次失败释放一次，无部分计数
        assertThat(capacity.released).hasSize(capacity.reserved.get());
        assertThat(capacity.settled).isEmpty();
    }

    @Test
    void authFailureFallsBackToSecondCandidateAndSucceeds() {
        adapter.error = ProviderFailure.http(500, null, "boom");
        adapter.failFirstN = 2;
        adapter.response = new ProviderChatResponse("ok", "stop", 10L, 5L, 15L, "ACTUAL", "req-2");
        UnifiedChatResponse response = pipeline.chat(context(request("assistant", false), null));
        assertThat(response.choices().get(0).message().content()).isEqualTo("ok");
        assertThat(adapter.invocations.get()).isEqualTo(3);
        assertThat(traceStore.statusOf(response.id())).isEqualTo("SUCCEEDED");
    }

    @Test
    void streamCommitsOnlyOnFirstContentAndSequencesFromZero() {
        adapter.streamScript = List.of(
                ProviderStreamChunk.content("你"),
                ProviderStreamChunk.content("好"),
                ProviderStreamChunk.usage(7L, 3L, 10L),
                ProviderStreamChunk.finish("stop"));
        List<Object> events = new java.util.ArrayList<>();
        pipeline.chatStream(context(request("assistant", true), null), new RecordingListener(events));

        List<UnifiedChatChunk> chunks = events.stream()
                .filter(e -> e instanceof UnifiedChatChunk)
                .map(e -> (UnifiedChatChunk) e).toList();
        assertThat(events.get(0)).isEqualTo("COMMIT");
        assertThat(chunks.get(0).lightAi().sequence()).isEqualTo(0);
        assertThat(chunks.get(0).choices().get(0).delta().role()).isEqualTo("assistant");
        assertThat(chunks.get(1).choices().get(0).delta().content()).isEqualTo("你");
        assertThat(chunks.get(2).choices().get(0).delta().content()).isEqualTo("好");
        assertThat(chunks.get(3).choices().get(0).finishReason()).isEqualTo("stop");
        // include_usage 默认 false → 不外发 Usage 块
        assertThat(chunks).noneMatch(chunk -> chunk.usage() != null);
        String traceId = chunks.get(0).id();
        assertThat(traceStore.statusOf(traceId)).isEqualTo("SUCCEEDED");
        assertThat(traceStore.committed(traceId)).isTrue();
    }

    @Test
    void streamUsageChunkSentWhenIncludeUsage() {
        adapter.streamScript = List.of(
                ProviderStreamChunk.content("hi"),
                ProviderStreamChunk.usage(7L, 3L, 10L),
                ProviderStreamChunk.finish("stop"));
        UnifiedChatRequest request = new UnifiedChatRequest("assistant",
                List.of(new ChatMessage("user", "hello")), true, null, null, null, null, null, null, null,
                new StreamOptions(true));
        List<Object> events = new java.util.ArrayList<>();
        pipeline.chatStream(context(request, null), new RecordingListener(events));
        List<UnifiedChatChunk> chunks = events.stream()
                .filter(e -> e instanceof UnifiedChatChunk)
                .map(e -> (UnifiedChatChunk) e).toList();
        assertThat(chunks).anyMatch(chunk -> chunk.usage() != null && chunk.choices().isEmpty());
    }

    @Test
    void streamFailureBeforeCommitSendsNothingAndRecovers() {
        adapter.failFirstN = 2;
        adapter.streamScript = List.of(ProviderStreamChunk.content("ok"), ProviderStreamChunk.finish("stop"));
        List<Object> events = new java.util.ArrayList<>();
        pipeline.chatStream(context(request("assistant", true), null), new RecordingListener(events));
        // 两次失败（提交前）不产生任何块；第三次成功按最终路径输出
        List<UnifiedChatChunk> chunks = events.stream()
                .filter(e -> e instanceof UnifiedChatChunk)
                .map(e -> (UnifiedChatChunk) e).toList();
        assertThat(events.get(0)).isEqualTo("COMMIT");
        assertThat(chunks).hasSize(3);
        assertThat(traceStore.statusOf(chunks.get(0).id())).isEqualTo("SUCCEEDED");
    }

    @Test
    void streamFailureAfterCommitIsInterruptedWithoutDone() {
        adapter.streamScript = java.util.List.of(
                ProviderStreamChunk.content("你"),
                ProviderStreamChunk.content("中断"));
        adapter.streamInterruptAfterCommit = true;
        List<Object> events = new java.util.ArrayList<>();
        pipeline.chatStream(context(request("assistant", true), null), new RecordingListener(events));
        assertThat(events).contains("ERROR");
        assertThat(events).doesNotContain("DONE");
        UnifiedError error = events.stream()
                .filter(e -> e instanceof UnifiedError)
                .map(e -> (UnifiedError) e).findFirst().orElseThrow();
        assertThat(error.code()).isEqualTo(ErrorCode.STREAM_INTERRUPTED.name());
        String traceId = events.stream().filter(e -> e instanceof UnifiedChatChunk)
                .map(e -> (UnifiedChatChunk) e).findFirst().orElseThrow().id();
        assertThat(traceStore.statusOf(traceId)).isEqualTo("STREAM_INTERRUPTED");
    }

    @Test
    void clientCancelBeforeAttemptFinalizesCancelled() {
        CancellationSignal signal = new CancellationSignal("t");
        signal.cancel("client gone");
        List<Object> events = new java.util.ArrayList<>();
        assertThatThrownBy(() -> pipeline.chatStream(context(request("assistant", true), signal),
                new RecordingListener(events)))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CLIENT_CANCELLED));
        assertThat(adapter.invocations.get()).isZero();
        assertThat(traceStore.createdTraceIds().stream()
                .anyMatch(id -> traceStore.statusOf(id).equals("CANCELLED")))
                .isTrue();
    }

    // ---------------------------------------------------------------- 夹具

    private static final class RecordingListener implements ChatPipeline.StreamListener {
        private final List<Object> events;

        RecordingListener(List<Object> events) {
            this.events = events;
        }

        @Override
        public void onCommit() {
            events.add("COMMIT");
        }

        @Override
        public void onChunk(UnifiedChatChunk chunk) {
            events.add(chunk);
        }

        @Override
        public void onError(UnifiedError error) {
            events.add("ERROR");
            events.add(error);
        }
    }

    /** 记录型容量：预占/结算/释放计数。 */
    static final class RecordingCapacity implements CapacityPort {
        final AtomicInteger reserved = new AtomicInteger();
        final List<String> settled = new java.util.ArrayList<>();
        final List<String> released = new java.util.ArrayList<>();

        @Override
        public Reservation reserve(String aliasId, String modelId, String credentialId, long estimatedTokens) {
            reserved.incrementAndGet();
            return new Reservation("r-" + reserved.get(), aliasId, modelId, credentialId);
        }

        @Override
        public void settle(String reservationId, long inputTokens, long outputTokens) {
            settled.add(reservationId);
        }

        @Override
        public void release(String reservationId) {
            released.add(reservationId);
        }
    }

    /** 可编程桩 Adapter：同步返回/失败、流式脚本、提交后中断。 */
    static final class StubAdapter implements ProviderAdapter {
        ProviderChatResponse response;
        ProviderFailure error;
        int failFirstN;
        List<ProviderStreamChunk> streamScript = List.of();
        boolean streamInterruptAfterCommit;
        final AtomicInteger invocations = new AtomicInteger();
        private int failures;

        @Override
        public String providerType() {
            return "STUB";
        }

        @Override
        public AdapterCapabilities capabilities() {
            return new AdapterCapabilities(true, true, true, false, List.of("FAKE"), 4,
                    java.util.Set.of("stop", "length"), List.of());
        }

        @Override
        public long estimateTokens(ProviderChatRequest request) {
            return 8;
        }

        @Override
        public ProviderChatResponse chat(ProviderCallContext context) {
            invocations.incrementAndGet();
            if (error != null && invocations.get() <= failFirstN) {
                throw new com.lightai.spi.provider.ProviderTransportException(error, null);
            }
            if (error != null && failFirstN == 0) {
                throw new com.lightai.spi.provider.ProviderTransportException(error, null);
            }
            return response;
        }

        @Override
        public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    invocations.incrementAndGet();
                    try {
                        if (error != null && invocations.get() <= failFirstN) {
                            subscriber.onError(new com.lightai.spi.provider.ProviderTransportException(error, null));
                            return;
                        }
                        for (ProviderStreamChunk chunk : streamScript) {
                            if (streamInterruptAfterCommit
                                    && chunk.type() == ProviderStreamChunk.Type.CONTENT
                                    && traceCommittedOnce) {
                                subscriber.onError(new com.lightai.spi.provider.ProviderTransportException(
                                        ProviderFailure.badResponse("connection reset mid-stream"), null));
                                return;
                            }
                            if (chunk.type() == ProviderStreamChunk.Type.CONTENT) {
                                traceCommittedOnce = true;
                            }
                            subscriber.onNext(chunk);
                        }
                        subscriber.onComplete();
                    } catch (RuntimeException e) {
                        // 订阅内异常（含提交前失败抛回管线）原样上传
                        subscriber.onError(e);
                    }
                }

                @Override
                public void cancel() {
                }
            });
        }

        private boolean traceCommittedOnce;

        @Override
        public ProviderErrorClassification classifyError(ProviderFailure failure) {
            return new ProviderErrorClassification(
                    failure.httpStatus() != null && failure.httpStatus() == 401
                            ? "PROVIDER_AUTH_FAILED" : "PROVIDER_SERVER_ERROR",
                    failure.httpStatus() == null || failure.httpStatus() >= 500,
                    !"PROVIDER_REQUEST_REJECTED".equals("x"), true, failure.httpStatus() != null);
        }
    }

    private static UnifiedChatRequest request(String alias, boolean stream) {
        return new UnifiedChatRequest(alias, List.of(new ChatMessage("user", "hello")),
                stream, null, null, null, null, null, null, null, null);
    }

    private static UnifiedChatRequest withTraceId(UnifiedChatRequest request, String traceId) {
        return new UnifiedChatRequest(request.model(), request.messages(), request.stream(),
                request.temperature(), request.topP(), request.maxTokens(), request.stop(),
                traceId, request.metadata(), request.providerOptions(), request.streamOptions());
    }

    private ChatPipeline.ChatContext context(UnifiedChatRequest request, CancellationSignal signal) {
        return new ChatPipeline.ChatContext(
                new AccessTokenPort.Principal("app-1", List.of()), request, signal);
    }

    private static ConfigSnapshotPort snapshot() {
        return () -> new ConfigSnapshotPort.ActiveSnapshot(7, List.of(
                new AliasView("alias-1", "assistant", "助理", true, List.of(CANDIDATE_A, CANDIDATE_B))));
    }

    private static RoutingPort routing() {
        return (alias, request, estimatedInput) -> new RoutingPort.RoutingResult(
                alias.enabledCandidates(), false, false);
    }

    private static CandidateView candidate(String id, String providerType, String modelId, long priority,
                                           String inputPrice, String outputPrice) {
        return new CandidateView(id, "provider-1", providerType, "pk-" + id, modelId, "pool-1",
                priority, 1, true, "FAKE", 8000L, 512L, true, true, true, true, true,
                null, null, null, null, 4, null, null, null,
                inputPrice, outputPrice, 1000, "USD");
    }

}
