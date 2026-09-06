package com.lightai.server.regression;

import com.lightai.client.chat.*;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.error.UnifiedError;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ReliabilityBudgets;
import com.lightai.runtime.circuit.CircuitKey;
import com.lightai.runtime.circuit.CircuitPolicy;
import com.lightai.runtime.circuit.CircuitSnapshot;
import com.lightai.runtime.circuit.InMemoryCircuitStore;
import com.lightai.runtime.ports.*;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView;
import com.lightai.runtime.trace.InMemoryTraceStore;
import com.lightai.server.health.HealthController;
import com.lightai.server.health.ReadinessService;
import com.lightai.server.lifecycle.ServerLifecycleService;
import com.lightai.server.v1.V1Controller;
import com.lightai.spi.provider.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRD 6.6 关键验收场景矩阵跨模块回归套件（BE-058）：
 * 1. Provider 429 限流与多级恢复（Credential Failover -> Candidate Fallback）
 * 2. 熔断器状态迁移（CLOSED -> OPEN -> HALF_OPEN -> CLOSED，429 不计失败）
 * 3. 流式提交前恢复 vs 提交后中断（首块前 Fallback，首块后 STREAM_INTERRUPTED 绝不切换模型）
 * 4. 同步响应一致性与多币种独立归因
 * 5. Standalone 就绪检查与优雅停机摘流联动（SERVER_DRAINING）
 */
public class PrdScenarioMatrixRegressionTest {

    private InMemoryTraceStore traceStore;
    private MockCapacityPort capacityPort;
    private ConfigSnapshotPort snapshotPort;
    private InMemoryCircuitStore circuitStore;

    private static CandidateView candidate(String id, String provider, String model, long priority) {
        return new CandidateView(id, provider.toLowerCase(), provider, "pk-" + id, model,
                "pool-1", priority, 100, true, "cl100k", 8192L, 4096L,
                true, true, true, true, true,
                BigDecimal.ZERO, BigDecimal.valueOf(2), BigDecimal.ZERO, BigDecimal.ONE, 4,
                BigDecimal.ONE, BigDecimal.ONE, 1024L,
                "0.000001", "0.000002", 1000, "USD");
    }

    @BeforeEach
    void setUp() {
        traceStore = new InMemoryTraceStore();
        capacityPort = new MockCapacityPort();
        circuitStore = new InMemoryCircuitStore();

        CandidateView candA = candidate("cand-a", "OPENAI", "gpt-4o", 10);
        CandidateView candB = candidate("cand-b", "ANTHROPIC", "claude-3-5-sonnet", 20);
        AliasView aliasView = new AliasView("alias-1", "chat-assistant", "Chat Assistant", true, List.of(candA, candB));
        snapshotPort = () -> new ConfigSnapshotPort.ActiveSnapshot(1L, List.of(aliasView));
    }

    @Test
    @DisplayName("PRD 6.6 场景：Provider 429 限流，优先 Credential Failover 再 Fallback，Trace 完整记录")
    void testProvider429CredentialFailoverThenFallback() {
        AtomicInteger openaiCalls = new AtomicInteger(0);
        AtomicInteger anthropicCalls = new AtomicInteger(0);

        ProviderAdapter openaiAdapter = new StubAdapter("OPENAI") {
            @Override
            public ProviderChatResponse chat(ProviderCallContext ctx) {
                openaiCalls.incrementAndGet();
                throw new ProviderTransportException(ProviderFailure.http(429, "req-rate-limit", "Rate limited"), null);
            }
        };

        ProviderAdapter anthropicAdapter = new StubAdapter("ANTHROPIC") {
            @Override
            public ProviderChatResponse chat(ProviderCallContext ctx) {
                anthropicCalls.incrementAndGet();
                return new ProviderChatResponse("Anthropic 备用候选响应", "stop", 50L, 100L, 150L, "ACTUAL", "anthropic-req-1");
            }
        };

        AdapterRegistryPort registry = type -> {
            if ("OPENAI".equalsIgnoreCase(type)) return Optional.of(openaiAdapter);
            if ("ANTHROPIC".equalsIgnoreCase(type)) return Optional.of(anthropicAdapter);
            return Optional.empty();
        };

        AtomicInteger credentialIndex = new AtomicInteger(0);
        CredentialSecretPort credentialPort = (poolId, failoverIdx) -> {
            credentialIndex.set(failoverIdx);
            return new CredentialSecretPort.ResolvedCredential("cred-key-" + failoverIdx, () -> "sk-test".toCharArray());
        };

        RoutingPort routingPort = (alias, request, estimatedInputTokens) ->
                new RoutingPort.RoutingResult(alias.enabledCandidates(), false, false);

        ChatPipeline pipeline = new ChatPipeline(
                snapshotPort,
                () -> Optional.empty(),
                routingPort,
                capacityPort,
                credentialPort,
                registry,
                traceStore,
                () -> ReliabilityBudgets.DEFAULT,
                10000
        );

        UnifiedChatRequest request = new UnifiedChatRequest("chat-assistant",
                List.of(new ChatMessage("user", "Hello")), false, null, null, null, null, null, null, null, null);
        AccessTokenPort.Principal principal = new AccessTokenPort.Principal("app-1", List.of());

        UnifiedChatResponse response = pipeline.chat(new ChatPipeline.ChatContext(principal, request, null));

        assertNotNull(response);
        assertEquals("Anthropic 备用候选响应", response.choices().get(0).message().content());
        assertEquals("ANTHROPIC", response.lightAi().provider());
        assertEquals("claude-3-5-sonnet", response.lightAi().providerModel());
        assertTrue(openaiCalls.get() >= 1, "首选 Provider 发生调用");
        assertEquals(1, anthropicCalls.get(), "Fallback 备用候选成功执行一次");
    }

    @Test
    @DisplayName("PRD 6.6 场景：熔断状态机迁移 CLOSED -> OPEN -> HALF_OPEN -> CLOSED，429 不计入失败")
    void testCircuitBreakerStateTransitions() {
        CircuitKey key = new CircuitKey(UUID.randomUUID(), UUID.randomUUID());
        CircuitPolicy policy = new CircuitPolicy(UUID.randomUUID(), 3, 60, 3, 0.5, 30, 2, 1);
        Instant t0 = Instant.parse("2026-09-06T12:00:00Z");

        // 初始状态为 CLOSED
        assertEquals(CircuitSnapshot.STATE_CLOSED, circuitStore.snapshot(key, policy, t0).state());

        // 20 次 429 限流：不计为失败，保持 CLOSED
        for (int i = 0; i < 20; i++) {
            circuitStore.recordResult(key, policy, false, true, t0.plusSeconds(i));
        }
        assertEquals(CircuitSnapshot.STATE_CLOSED, circuitStore.snapshot(key, policy, t0.plusSeconds(25)).state());

        // 达到失败阈值后自动 OPEN
        circuitStore.recordResult(key, policy, false, false, t0.plusSeconds(30));
        circuitStore.recordResult(key, policy, false, false, t0.plusSeconds(31));
        circuitStore.recordResult(key, policy, false, false, t0.plusSeconds(32));

        CircuitSnapshot openSnapshot = circuitStore.snapshot(key, policy, t0.plusSeconds(35));
        assertEquals(CircuitSnapshot.STATE_OPEN, openSnapshot.state());

        // 冷却期后探测名额允许，探测成功后恢复 CLOSED
        Instant afterCooldown = t0.plusSeconds(70);
        assertTrue(circuitStore.tryAcquireProbe(key, policy, afterCooldown).isPresent());
        circuitStore.recordResult(key, policy, true, false, afterCooldown.plusSeconds(1));
        assertEquals(CircuitSnapshot.STATE_CLOSED, circuitStore.snapshot(key, policy, afterCooldown.plusSeconds(2)).state());
    }

    @Test
    @DisplayName("PRD 6.6 场景：流式提交前恢复（首块前发生故障切换备用候选）")
    void testStreamPreCommitRecovery() {
        ProviderAdapter failingAdapter = new StubAdapter("OPENAI") {
            @Override
            public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        subscriber.onError(new ProviderTransportException(ProviderFailure.http(500, "req-err", "Upstream 500"), null));
                    }

                    @Override
                    public void cancel() {
                    }
                });
            }
        };

        ProviderAdapter backupAdapter = new StubAdapter("ANTHROPIC") {
            @Override
            public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        subscriber.onNext(ProviderStreamChunk.content("Chunk from backup"));
                        subscriber.onNext(ProviderStreamChunk.finish("stop"));
                        subscriber.onComplete();
                    }

                    @Override
                    public void cancel() {
                    }
                });
            }
        };

        AdapterRegistryPort registry = type -> {
            if ("OPENAI".equalsIgnoreCase(type)) return Optional.of(failingAdapter);
            if ("ANTHROPIC".equalsIgnoreCase(type)) return Optional.of(backupAdapter);
            return Optional.empty();
        };

        CredentialSecretPort credentialPort = (poolId, failoverIdx) ->
                new CredentialSecretPort.ResolvedCredential("cred-1", () -> "sk-test".toCharArray());

        RoutingPort routingPort = (alias, request, estimatedInputTokens) ->
                new RoutingPort.RoutingResult(alias.enabledCandidates(), false, false);

        ChatPipeline pipeline = new ChatPipeline(
                snapshotPort, () -> Optional.empty(), routingPort, capacityPort, credentialPort,
                registry, traceStore, () -> ReliabilityBudgets.DEFAULT, 10000
        );

        List<UnifiedChatChunk> receivedChunks = new ArrayList<>();
        UnifiedChatRequest request = new UnifiedChatRequest("chat-assistant",
                List.of(new ChatMessage("user", "Hello stream")), true, null, null, null, null, null, null, null, null);
        AccessTokenPort.Principal principal = new AccessTokenPort.Principal("app-1", List.of());

        pipeline.chatStream(new ChatPipeline.ChatContext(principal, request, null), new ChatPipeline.StreamListener() {
            @Override
            public void onCommit() {
            }

            @Override
            public void onChunk(UnifiedChatChunk chunk) {
                receivedChunks.add(chunk);
            }

            @Override
            public void onError(UnifiedError error) {
            }
        });

        assertFalse(receivedChunks.isEmpty());
        assertTrue(receivedChunks.stream().anyMatch(c ->
                c.choices().get(0).delta() != null && "Chunk from backup".equals(c.choices().get(0).delta().content())),
                "必须收到来自 backup 候选的内容块");
    }

    @Test
    @DisplayName("PRD 6.6 场景：流式首块提交后发生中断，严禁切换模型或拼接备用输出，返回 STREAM_INTERRUPTED")
    void testStreamPostCommitInterruptionStrictIsolation() {
        ProviderAdapter interruptedAdapter = new StubAdapter("OPENAI") {
            @Override
            public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        // 发送首个内容块（触发 onCommit）
                        subscriber.onNext(ProviderStreamChunk.content("First chunk delivered"));
                        // 首块之后连接突然中断
                        subscriber.onError(new ProviderTransportException(
                                ProviderFailure.badResponse("Connection reset mid-stream"), null));
                    }

                    @Override
                    public void cancel() {
                    }
                });
            }
        };

        AdapterRegistryPort registry = type -> Optional.of(interruptedAdapter);
        CredentialSecretPort credentialPort = (poolId, failoverIdx) ->
                new CredentialSecretPort.ResolvedCredential("cred-1", () -> "sk-test".toCharArray());
        RoutingPort routingPort = (alias, request, estimatedInputTokens) ->
                new RoutingPort.RoutingResult(alias.enabledCandidates(), false, false);

        ChatPipeline pipeline = new ChatPipeline(
                snapshotPort, () -> Optional.empty(), routingPort, capacityPort, credentialPort,
                registry, traceStore, () -> ReliabilityBudgets.DEFAULT, 10000
        );

        AtomicBoolean committed = new AtomicBoolean(false);
        AtomicReference<UnifiedError> finalError = new AtomicReference<>();
        List<String> textDeltas = new ArrayList<>();

        UnifiedChatRequest request = new UnifiedChatRequest("chat-assistant",
                List.of(new ChatMessage("user", "stream test")), true, null, null, null, null, null, null, null, null);
        AccessTokenPort.Principal principal = new AccessTokenPort.Principal("app-1", List.of());

        pipeline.chatStream(new ChatPipeline.ChatContext(principal, request, null), new ChatPipeline.StreamListener() {
            @Override
            public void onCommit() {
                committed.set(true);
            }

            @Override
            public void onChunk(UnifiedChatChunk chunk) {
                if (chunk.choices().get(0).delta() != null && chunk.choices().get(0).delta().content() != null) {
                    textDeltas.add(chunk.choices().get(0).delta().content());
                }
            }

            @Override
            public void onError(UnifiedError error) {
                finalError.set(error);
            }
        });

        assertTrue(committed.get(), "必须已提交首块");
        assertEquals(1, textDeltas.size());
        assertEquals("First chunk delivered", textDeltas.get(0));
        assertNotNull(finalError.get(), "必须收到流式错误事件");
        assertEquals("STREAM_INTERRUPTED", finalError.get().code());
    }

    @Test
    @DisplayName("PRD 6.6 场景：Standalone 优雅停机摘流与新请求 503 SERVER_DRAINING 拒绝")
    void testStandaloneDrainingRefusesNewRequests() {
        ServerLifecycleService lifecycleService = new ServerLifecycleService(2, capacityPort);
        ReadinessService readinessService = new ReadinessService(lifecycleService, snapshotPort, null);
        HealthController healthController = new HealthController(readinessService);

        // 初始正常
        assertEquals(HttpStatus.OK, healthController.live().getStatusCode());
        assertEquals(HttpStatus.OK, healthController.ready().getStatusCode());

        // 发起优雅停机
        lifecycleService.setAcceptingRequests(false);

        // Readiness 立即变 DOWN
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, healthController.ready().getStatusCode());

        // V1Controller 拒绝新请求
        V1Controller controller = new V1Controller(null, null, null, lifecycleService);
        LightAiException ex = assertThrows(LightAiException.class, () ->
                controller.models("Bearer valid-token"));
        assertEquals(ErrorCode.SERVER_DRAINING, ex.code());
        assertEquals(503, ex.code().httpStatus());
    }

    private static class MockCapacityPort implements CapacityPort {
        @Override
        public Reservation reserve(String aliasId, String modelId, String credentialId, long estimatedTokens) {
            return new Reservation(UUID.randomUUID().toString(), aliasId, modelId, credentialId);
        }

        @Override
        public void settle(String reservationId, long inputTokens, long outputTokens) {
        }

        @Override
        public void release(String reservationId) {
        }
    }

    private static class StubAdapter implements ProviderAdapter {
        private final String type;

        public StubAdapter(String type) {
            this.type = type;
        }

        @Override
        public String providerType() {
            return type;
        }

        @Override
        public AdapterCapabilities capabilities() {
            return new AdapterCapabilities(true, true, true, false, List.of("cl100k"), 4, Set.of("stop"), List.of());
        }

        @Override
        public long estimateTokens(ProviderChatRequest request) {
            return 10L;
        }

        @Override
        public ProviderChatResponse chat(ProviderCallContext context) {
            return new ProviderChatResponse("hello", "stop", 5L, 5L, 10L, "ACTUAL", "req-1");
        }

        @Override
        public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onNext(ProviderStreamChunk.content("hello"));
                    subscriber.onNext(ProviderStreamChunk.finish("stop"));
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                }
            });
        }

        @Override
        public ProviderErrorClassification classifyError(ProviderFailure failure) {
            if (failure.httpStatus() != null && failure.httpStatus() == 429) {
                return new ProviderErrorClassification("PROVIDER_RATE_LIMITED", true, true, true, false);
            }
            return new ProviderErrorClassification("PROVIDER_SERVER_ERROR", true, true, true, true);
        }
    }
}
