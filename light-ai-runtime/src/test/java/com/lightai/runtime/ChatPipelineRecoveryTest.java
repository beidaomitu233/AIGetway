package com.lightai.runtime;

import com.lightai.client.chat.ChatMessage;
import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.CancellationSignal;
import com.lightai.runtime.chat.ReliabilityBudgets;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.AdapterRegistryPort;
import com.lightai.runtime.ports.ApplicationQuotaPort;
import com.lightai.runtime.ports.CapacityPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView;
import com.lightai.runtime.ports.CredentialHealthPort;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.runtime.ports.RoutingPort;
import com.lightai.runtime.trace.InMemoryTraceStore;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderFailure;
import com.lightai.spi.provider.ProviderStreamChunk;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-P22 恢复与准入语义（PRD 9.5/10.3）：
 * 同 Key 重试先于换 Key、429/认证失败换 Key 并回写健康、同优先级候选预算独立、
 * 429 返回维度与 retry_after、客户端取消向上游取消订阅并收敛唯一终态。
 */
class ChatPipelineRecoveryTest {

    private final RecordingHealth health = new RecordingHealth();

    // ---------------------------------------------------------------- 同步恢复

    @Test
    void transientErrorRetriesSameKeyBeforeCredentialFailover() {
        ChatPipelineTest.StubAdapter adapter = new ChatPipelineTest.StubAdapter();
        adapter.error = ProviderFailure.http(500, null, "boom");
        adapter.failFirstN = 2;
        adapter.response = new ProviderChatResponse("ok", "stop", 5L, 5L, 10L, "ACTUAL", "req-r");
        List<String> resolvedKeys = new CopyOnWriteArrayList<>();
        ChatPipeline pipeline = pipeline(adapter, credentialsRecording(resolvedKeys),
                () -> ReliabilityBudgets.DEFAULT);

        var response = pipeline.chat(context(request(false), "retry-order"));

        assertThat(response.choices().get(0).message().content()).isEqualTo("ok");
        // 瞬时错误：先同 Key 重试，之后才换 Key（PRD 9.5 顺序）
        assertThat(resolvedKeys).containsExactly("channel-cand-a-c0", "channel-cand-a-c0", "channel-cand-a-c1");
        assertThat(adapter.invocations.get()).isEqualTo(3);
    }

    @Test
    void upstreamRateLimitSkipsSameKeyRetryWritesCooldownAndFailsOver() {
        ChatPipelineTest.StubAdapter adapter = new ChatPipelineTest.StubAdapter() {
            @Override
            public com.lightai.spi.provider.ProviderErrorClassification classifyError(
                    com.lightai.spi.provider.ProviderFailure failure) {
                return new com.lightai.spi.provider.ProviderErrorClassification(
                        failure.httpStatus() != null && failure.httpStatus() == 429
                                ? "PROVIDER_RATE_LIMITED" : "PROVIDER_SERVER_ERROR",
                        true, true, true, true);
            }
        };
        adapter.error = ProviderFailure.http(429, null, "rate limited");
        adapter.failFirstN = 1;
        adapter.response = new ProviderChatResponse("ok", "stop", 5L, 5L, 10L, "ACTUAL", "req-429");
        List<String> resolvedKeys = new CopyOnWriteArrayList<>();
        ChatPipeline pipeline = pipeline(adapter, credentialsRecording(resolvedKeys),
                () -> ReliabilityBudgets.DEFAULT);

        var response = pipeline.chat(context(request(false), "rate-limit"));

        assertThat(response.choices().get(0).message().content()).isEqualTo("ok");
        // 429 不重试同 Key，直接换 Key
        assertThat(resolvedKeys).containsExactly("channel-cand-a-c0", "channel-cand-a-c1");
        assertThat(adapter.invocations.get()).isEqualTo(2);
        assertThat(health.rateLimited).hasSize(1);
        assertThat(health.rateLimited.get(0).errorCode()).isEqualTo("PROVIDER_RATE_LIMITED");
        assertThat(health.rateLimited.get(0).resetAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(health.authFailed).isEmpty();
    }

    @Test
    void upstreamAuthFailureMarksCredentialInvalidAndFailsOver() {
        ChatPipelineTest.StubAdapter adapter = new ChatPipelineTest.StubAdapter();
        adapter.error = ProviderFailure.http(401, null, "invalid key");
        adapter.failFirstN = 1;
        adapter.response = new ProviderChatResponse("ok", "stop", 5L, 5L, 10L, "ACTUAL", "req-401");
        List<String> resolvedKeys = new CopyOnWriteArrayList<>();
        ChatPipeline pipeline = pipeline(adapter, credentialsRecording(resolvedKeys),
                () -> ReliabilityBudgets.DEFAULT);

        pipeline.chat(context(request(false), "auth-fail"));

        assertThat(resolvedKeys).containsExactly("channel-cand-a-c0", "channel-cand-a-c1");
        assertThat(health.authFailed).hasSize(1);
        assertThat(health.rateLimited).isEmpty();
    }

    @Test
    void exhaustedSamePriorityBudgetBlocksNextPriorityFallback() {
        // 同优先级预算为 0：同级候选不可切换，且不得跨级消耗下一优先级预算
        ConfigSnapshotPort snapshots = () -> new ConfigSnapshotPort.ActiveSnapshot(7, List.of(
                new AliasView("alias-1", "assistant", "助理", true, List.of(
                        candidate("p1a", 1), candidate("p1b", 1), candidate("p2a", 2)))));
        ChatPipelineTest.StubAdapter adapter = new ChatPipelineTest.StubAdapter();
        adapter.error = ProviderFailure.http(429, null, "rate limited");
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        ChatPipeline pipeline = new ChatPipeline(snapshots, () -> Optional.empty(),
                fixedRouting(), new ChatPipelineTest.RecordingCapacity(), null,
                (channelId, index) -> new CredentialSecretPort.ResolvedCredential(
                        UUID.randomUUID().toString(), () -> "sk-test".toCharArray()),
                null, type -> Optional.of(adapter), traceStore, ApplicationQuotaPort.unlimited(),
                () -> new ReliabilityBudgets(0, 0, 0, 1), 30_000, health);

        try {
            pipeline.chat(context(request(false), "priority-blocked"));
            throw new AssertionError("expected failure");
        } catch (LightAiException expected) {
            assertThat(expected.code()).isEqualTo(ErrorCode.ALL_CANDIDATES_FAILED);
        }
        assertThat(adapter.invocations.get()).isEqualTo(1);
        assertThat(traceStore.statusOf("priority-blocked")).isEqualTo("FAILED");
    }

    @Test
    void samePriorityThenNextPriorityFallbackWithinIndependentBudgets() {
        ConfigSnapshotPort snapshots = () -> new ConfigSnapshotPort.ActiveSnapshot(7, List.of(
                new AliasView("alias-1", "assistant", "助理", true, List.of(
                        candidate("p1a", 1), candidate("p1b", 1), candidate("p2a", 2)))));
        ChatPipelineTest.StubAdapter adapter = new ChatPipelineTest.StubAdapter();
        adapter.error = ProviderFailure.http(429, null, "rate limited");
        adapter.failFirstN = 2;
        adapter.response = new ProviderChatResponse("ok", "stop", 5L, 5L, 10L, "ACTUAL", "req-p2");
        List<String> routedCandidates = new CopyOnWriteArrayList<>();
        AdapterRegistryPort counting = type -> {
            routedCandidates.add("call");
            return Optional.of(adapter);
        };
        ChatPipeline pipeline = new ChatPipeline(snapshots, () -> Optional.empty(),
                fixedRouting(), new ChatPipelineTest.RecordingCapacity(), null,
                (channelId, index) -> new CredentialSecretPort.ResolvedCredential(
                        UUID.randomUUID().toString(), () -> "sk-test".toCharArray()),
                null, counting, new InMemoryTraceStore(), ApplicationQuotaPort.unlimited(),
                () -> new ReliabilityBudgets(0, 0, 1, 1), 30_000, health);

        var response = pipeline.chat(context(request(false), "priority-ok"));

        assertThat(response.choices().get(0).message().content()).isEqualTo("ok");
        // p1a → 同级候选 p1b → 下一优先级 p2a
        assertThat(adapter.invocations.get()).isEqualTo(3);
        assertThat(routedCandidates).hasSize(3);
    }

    // ---------------------------------------------------------------- 429 维度与 retry_after

    @Test
    void capacityLimitedCarriesDimensionAndRetryAfter() {
        ChatPipelineTest.StubAdapter adapter = new ChatPipelineTest.StubAdapter();
        CapacityPort limited = new CapacityPort() {
            @Override
            public Reservation reserve(String alias, String model, String credential, long estimatedTokens) {
                throw new com.lightai.runtime.capacity.CapacityStore.CapacityLimitedException(
                        "APPLICATION", "RPM");
            }

            @Override public void settle(String reservationId, long inputTokens, long outputTokens) { }
            @Override public void release(String reservationId) { }
        };
        ChatPipeline pipeline = new ChatPipeline(snapshot(), () -> Optional.empty(),
                fixedRouting(), limited, null,
                (channelId, index) -> new CredentialSecretPort.ResolvedCredential(
                        "channel-c0", () -> "sk-test".toCharArray()),
                null, type -> Optional.of(adapter), new InMemoryTraceStore(),
                ApplicationQuotaPort.unlimited(), () -> new ReliabilityBudgets(0, 0, 0),
                30_000, health);
        ChatPipeline.ChatContext context = new ChatPipeline.ChatContext(
                new AccessTokenPort.Principal("app-1", List.of()),
                withTraceId(request(false), "cap-429"), null);

        try {
            pipeline.chat(context);
            throw new AssertionError("expected CAPACITY_LIMITED");
        } catch (LightAiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.CAPACITY_LIMITED);
            assertThat(e.retryAfterMs()).isNotNull();
            assertThat(e.retryAfterMs()).isStrictlyBetween(0L, 60_001L);
            assertThat(e.issues()).anySatisfy(issue -> {
                assertThat(issue.field()).isEqualTo("scope");
                assertThat(issue.code()).isEqualTo("APPLICATION");
            });
            assertThat(e.issues()).anySatisfy(issue -> {
                assertThat(issue.field()).isEqualTo("metric");
                assertThat(issue.code()).isEqualTo("RPM");
            });
        }
        assertThat(adapter.invocations.get()).isZero();
    }

    // ---------------------------------------------------------------- 流式取消传播

    @Test
    void streamCancellationCancelsUpstreamSubscriptionAndConvergesTerminalState() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        var cancelProceed = new java.util.concurrent.CountDownLatch(1);
        ChatPipelineTest.StubAdapter adapter = new ChatPipelineTest.StubAdapter() {
            @Override
            public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {
                        subscriber.onNext(ProviderStreamChunk.content("before-cancel"));
                        try {
                            cancelProceed.await(2, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        subscriber.onNext(ProviderStreamChunk.content("after-cancel"));
                        if (!cancelled.get()) {
                            subscriber.onComplete();
                        }
                    }

                    @Override public void cancel() {
                        cancelled.set(true);
                    }
                });
            }
        };
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        ChatPipeline pipeline = new ChatPipeline(snapshot(), () -> Optional.empty(),
                fixedRouting(), new ChatPipelineTest.RecordingCapacity(), null,
                (channelId, index) -> new CredentialSecretPort.ResolvedCredential(
                        UUID.randomUUID().toString(), () -> "sk-test".toCharArray()),
                null, type -> Optional.of(adapter), traceStore, ApplicationQuotaPort.unlimited(),
                () -> ReliabilityBudgets.DEFAULT, 30_000, health);
        CancellationSignal signal = new CancellationSignal("stream-cancel");
        List<Object> events = new CopyOnWriteArrayList<>();
        ChatPipeline.StreamListener listener = new ChatPipeline.StreamListener() {
            @Override public void onCommit() {
                events.add("COMMIT");
            }

            @Override public void onChunk(UnifiedChatChunk chunk) {
                events.add(chunk);
                if (events.stream().filter(e -> e instanceof UnifiedChatChunk).count() == 1) {
                    signal.cancel("client-disconnected");
                    cancelProceed.countDown();
                }
            }

            @Override public void onError(com.lightai.client.error.UnifiedError error) {
                events.add("ERROR");
            }
        };
        ChatPipeline.ChatContext context = new ChatPipeline.ChatContext(
                new AccessTokenPort.Principal("app-1", List.of()),
                withTraceId(request(true), "stream-cancel"), signal);
        Thread runner = new Thread(() -> pipeline.chatStream(context, listener));
        runner.start();
        runner.join(5_000);

        assertThat(cancelled.get()).isTrue();
        assertThat(traceStore.statusOf("stream-cancel")).isEqualTo("STREAM_INTERRUPTED");
        assertThat(traceStore.committed("stream-cancel")).isTrue();
    }

    // ---------------------------------------------------------------- 夹具

    private CredentialSecretPort credentialsRecording(List<String> resolvedKeys) {
        return (channelId, index) -> {
            resolvedKeys.add(channelId + "-c" + index);
            // 健康回写仅对合法 UUID 生效，与真实存储一致
            UUID credentialId = UUID.nameUUIDFromBytes(
                    (channelId + "-c" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new CredentialSecretPort.ResolvedCredential(credentialId.toString(),
                    () -> "sk-test".toCharArray(), "sk-****" + index);
        };
    }

    private ChatPipeline pipeline(ProviderAdapter adapter, CredentialSecretPort credentials,
                                  ReliabilityBudgets.Port budgets) {
        return new ChatPipeline(snapshot(), () -> Optional.empty(), fixedRouting(),
                new ChatPipelineTest.RecordingCapacity(), null, credentials, null,
                type -> Optional.of(adapter), new InMemoryTraceStore(),
                ApplicationQuotaPort.unlimited(), budgets, 30_000, health);
    }

    private static ChatPipeline.ChatContext context(UnifiedChatRequest request, String traceId) {
        return new ChatPipeline.ChatContext(new AccessTokenPort.Principal("app-1", List.of()),
                withTraceId(request, traceId), null);
    }

    private static UnifiedChatRequest request(boolean stream) {
        return new UnifiedChatRequest("assistant", List.of(new ChatMessage("user", "hello")),
                stream, null, null, null, null, null, null, null, null);
    }

    private static UnifiedChatRequest withTraceId(UnifiedChatRequest request, String traceId) {
        return new UnifiedChatRequest(request.model(), request.messages(), request.stream(),
                request.temperature(), request.topP(), request.maxTokens(), request.stop(),
                traceId, request.metadata(), request.providerOptions(), request.streamOptions());
    }

    private static ConfigSnapshotPort snapshot() {
        return () -> new ConfigSnapshotPort.ActiveSnapshot(7, List.of(
                new AliasView("alias-1", "assistant", "助理", true, List.of(
                        candidate("cand-a", 1)))));
    }

    private static RoutingPort fixedRouting() {
        return (alias, request, estimatedInput) -> new RoutingPort.RoutingResult(
                alias.enabledCandidates(), false, false);
    }

    private static CandidateView candidate(String id, long priority) {
        return new CandidateView(id, "channel-" + id, "OPENAI", "pk-" + id, "model-" + id,
                priority, 1, true, "FAKE", 8000L, 512L, true, true, true, true, true,
                null, null, null, null, 4, null, null, null,
                "0.00000015", "0.00000060", 1000, "USD",
                "https://provider.test/v1", null, 3000, 120000, Map.of());
    }

    /** 记录健康回写调用。 */
    private static final class RecordingHealth implements CredentialHealthPort {
        record RateLimit(UUID credentialId, Instant resetAt, String errorCode) {
        }

        final List<RateLimit> rateLimited = new ArrayList<>();
        final List<UUID> authFailed = new ArrayList<>();

        @Override
        public void markRateLimited(UUID channelCredentialId, Instant resetAt, String errorCode, String summary) {
            rateLimited.add(new RateLimit(channelCredentialId, resetAt, errorCode));
        }

        @Override
        public void markAuthFailed(UUID channelCredentialId, String errorCode, String summary) {
            authFailed.add(channelCredentialId);
        }
    }
}
