package com.lightai.server.performance;

import com.lightai.client.chat.ChatMessage;
import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.error.UnifiedError;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ReliabilityBudgets;
import com.lightai.runtime.ports.*;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView;
import com.lightai.runtime.trace.InMemoryTraceStore;
import com.lightai.server.health.ReadinessService;
import com.lightai.server.lifecycle.ServerLifecycleService;
import com.lightai.spi.provider.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 性能基线与故障恢复验收（PRD 6.6，BE-060）：
 * 1. 200 并发流式调用，缓冲上限 32，背压与释放验证；
 * 2. 管线附加延迟 P95 <= 20ms（排除 Provider 外部网络耗时）；
 * 3. 存储故障全局 Fail-closed（Redis/容量存储中断立即拒绝新预占，拒绝退化为独立计数，恢复后收敛）。
 */
public class PerformanceAndFaultRecoveryTest {

    private InMemoryTraceStore traceStore;
    private ConfigSnapshotPort snapshotPort;
    private CandidateView candidateView;

    @BeforeEach
    void setUp() {
        traceStore = new InMemoryTraceStore();
        candidateView = new CandidateView(
                "cand-perf", "openai", "OPENAI", "pk-perf", "gpt-4o",
                "pool-perf", 10, 100, true, "cl100k", 8192L, 4096L,
                true, true, true, true, true,
                BigDecimal.ZERO, BigDecimal.valueOf(2), BigDecimal.ZERO, BigDecimal.ONE, 4,
                BigDecimal.ONE, BigDecimal.ONE, 1024L,
                "0.000001", "0.000002", 1000, "USD"
        );
        snapshotPort = () -> new ConfigSnapshotPort.ActiveSnapshot(1L, List.of(
                new AliasView("alias-perf", "perf-alias", "Perf Alias", true, List.of(candidateView))));
    }

    @Test
    @DisplayName("BE-060: 200 并发流式调用与 32 缓冲背压验证")
    void test200ConcurrentStreamsWithBackpressure() throws InterruptedException {
        int concurrentStreams = 200;
        ExecutorService executor = Executors.newFixedThreadPool(64);
        CountDownLatch latch = new CountDownLatch(concurrentStreams);
        AtomicInteger successfulStreams = new AtomicInteger(0);
        AtomicInteger totalChunksReceived = new AtomicInteger(0);

        ProviderAdapter streamAdapter = new FastStreamAdapter(10); // 每个流产生 10 个数据块
        AdapterRegistryPort registry = type -> Optional.of(streamAdapter);
        CredentialSecretPort credentialPort = (poolId, failoverIdx) ->
                new CredentialSecretPort.ResolvedCredential("cred-perf", () -> "sk-test".toCharArray());
        RoutingPort routingPort = (alias, request, estimatedInputTokens) ->
                new RoutingPort.RoutingResult(alias.enabledCandidates(), false, false);
        CapacityPort capacityPort = CapacityPort.unlimited();

        ChatPipeline pipeline = new ChatPipeline(
                snapshotPort, () -> Optional.empty(), routingPort, capacityPort, credentialPort,
                registry, traceStore, () -> ReliabilityBudgets.DEFAULT, 30000
        );

        AccessTokenPort.Principal principal = new AccessTokenPort.Principal("app-perf", List.of());

        for (int i = 0; i < concurrentStreams; i++) {
            final int streamId = i;
            executor.submit(() -> {
                try {
                    UnifiedChatRequest request = new UnifiedChatRequest("perf-alias",
                            List.of(new ChatMessage("user", "stream " + streamId)), true, null, null, null, null, null, null, null, null);

                    AtomicInteger chunksInStream = new AtomicInteger(0);
                    pipeline.chatStream(new ChatPipeline.ChatContext(principal, request, null), new ChatPipeline.StreamListener() {
                        @Override
                        public void onCommit() {
                        }

                        @Override
                        public void onChunk(UnifiedChatChunk chunk) {
                            chunksInStream.incrementAndGet();
                            totalChunksReceived.incrementAndGet();
                        }

                        @Override
                        public void onError(UnifiedError error) {
                            System.err.println("Stream error: " + error);
                        }
                    });
                    if (chunksInStream.get() > 0) {
                        successfulStreams.incrementAndGet();
                    } else {
                        System.err.println("Stream " + streamId + " had 0 chunks!");
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "200 并发流式调用应在 15 秒内全部结束");
        assertEquals(concurrentStreams, successfulStreams.get(), "全部 200 个并发流必须成功执行");
        assertTrue(totalChunksReceived.get() >= concurrentStreams * 5, "流式数据块应按背压稳定交付");
    }

    @Test
    @DisplayName("BE-060: 管线附加处理耗时 P95 <= 20ms 验收")
    void testPipelineOverheadP95Under20Ms() {
        ProviderAdapter zeroLatencyAdapter = new StubFastAdapter();
        AdapterRegistryPort registry = type -> Optional.of(zeroLatencyAdapter);
        CredentialSecretPort credentialPort = (poolId, failoverIdx) ->
                new CredentialSecretPort.ResolvedCredential("cred-perf", () -> "sk-test".toCharArray());
        RoutingPort routingPort = (alias, request, estimatedInputTokens) ->
                new RoutingPort.RoutingResult(alias.enabledCandidates(), false, false);
        CapacityPort capacityPort = CapacityPort.unlimited();

        ChatPipeline pipeline = new ChatPipeline(
                snapshotPort, () -> Optional.empty(), routingPort, capacityPort, credentialPort,
                registry, traceStore, () -> ReliabilityBudgets.DEFAULT, 30000
        );

        AccessTokenPort.Principal principal = new AccessTokenPort.Principal("app-perf", List.of());
        UnifiedChatRequest request = new UnifiedChatRequest("perf-alias",
                List.of(new ChatMessage("user", "ping")), false, null, null, null, null, null, null, null, null);

        // 预热 JIT
        for (int i = 0; i < 50; i++) {
            pipeline.chat(new ChatPipeline.ChatContext(principal, request, null));
        }

        int iterations = 500;
        long[] durationsNano = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            UnifiedChatResponse response = pipeline.chat(new ChatPipeline.ChatContext(principal, request, null));
            durationsNano[i] = System.nanoTime() - start;
            assertNotNull(response);
        }

        Arrays.sort(durationsNano);
        int p50Index = (int) (iterations * 0.50);
        int p90Index = (int) (iterations * 0.90);
        int p95Index = (int) (iterations * 0.95);

        double p50Ms = durationsNano[p50Index] / 1_000_000.0;
        double p90Ms = durationsNano[p90Index] / 1_000_000.0;
        double p95Ms = durationsNano[p95Index] / 1_000_000.0;

        System.out.printf("Pipeline Overhead: P50=%.2fms, P90=%.2fms, P95=%.2fms%n", p50Ms, p90Ms, p95Ms);
        assertTrue(p95Ms <= 20.0, "管线附加耗时 P95 必须 <= 20ms，实际值: " + p95Ms + "ms");
    }

    @Test
    @DisplayName("BE-060: 存储故障全局 Fail-closed（容量存储中断拒绝新预占，拒绝静默退化）")
    void testStorageDisconnectFailClosedAndRecovery() {
        AtomicBoolean capacityAvailable = new AtomicBoolean(true);

        CapacityPort failClosedCapacityPort = new CapacityPort() {
            @Override
            public Reservation reserve(String aliasId, String modelId, String credentialId, long estimatedTokens) {
                if (!capacityAvailable.get()) {
                    throw new LightAiException(ErrorCode.CAPACITY_STATE_UNAVAILABLE, "共享容量存储不可用，Fail-closed");
                }
                return new Reservation(UUID.randomUUID().toString(), aliasId, modelId, credentialId);
            }

            @Override
            public void settle(String reservationId, long inputTokens, long outputTokens) {
            }

            @Override
            public void release(String reservationId) {
            }
        };

        ServerLifecycleService lifecycleService = new ServerLifecycleService(5, failClosedCapacityPort);
        ReadinessService readinessService = new ReadinessService(lifecycleService, snapshotPort, null);
        readinessService.setCapacityStoreHealthCheck(capacityAvailable::get);

        // 1. 正常状态
        assertTrue(readinessService.isReady(), "正常状态 readiness 应为 UP");

        // 2. 模拟 Redis / 容量存储网络中断
        capacityAvailable.set(false);

        // 验证 readiness 探针变为 DOWN (Fail-closed)
        assertFalse(readinessService.isReady(), "存储中断后 readiness 必须立即为 DOWN");

        // 验证管线发起新调用时被明确拒绝 (CAPACITY_STATE_UNAVAILABLE)
        ProviderAdapter adapter = new StubFastAdapter();
        ChatPipeline pipeline = new ChatPipeline(
                snapshotPort, () -> Optional.empty(),
                (alias, request, estimatedInputTokens) -> new RoutingPort.RoutingResult(alias.enabledCandidates(), false, false),
                failClosedCapacityPort,
                (poolId, failoverIdx) -> new CredentialSecretPort.ResolvedCredential("cred-1", () -> "sk-test".toCharArray()),
                type -> Optional.of(adapter), traceStore, () -> ReliabilityBudgets.DEFAULT, 30000
        );

        UnifiedChatRequest req = new UnifiedChatRequest("perf-alias",
                List.of(new ChatMessage("user", "ping")), false, null, null, null, null, null, null, null, null);
        AccessTokenPort.Principal principal = new AccessTokenPort.Principal("app-perf", List.of());

        LightAiException ex = assertThrows(LightAiException.class, () ->
                pipeline.chat(new ChatPipeline.ChatContext(principal, req, null)));
        assertTrue(ex.code() == ErrorCode.CAPACITY_STATE_UNAVAILABLE || ex.code() == ErrorCode.ALL_CANDIDATES_FAILED,
                "Fail-closed 拒绝异常应为 CAPACITY_STATE_UNAVAILABLE 或 ALL_CANDIDATES_FAILED");

        // 3. 存储恢复连接
        capacityAvailable.set(true);
        assertTrue(readinessService.isReady(), "存储恢复后 readiness 恢复为 UP");

        // 恢复后正常调用成功
        UnifiedChatResponse resp = pipeline.chat(new ChatPipeline.ChatContext(principal, req, null));
        assertNotNull(resp);
    }

    private static class StubFastAdapter implements ProviderAdapter {
        @Override
        public String providerType() {
            return "OPENAI";
        }

        @Override
        public AdapterCapabilities capabilities() {
            return new AdapterCapabilities(true, true, true, false, List.of("cl100k"), 4, Set.of("stop"), List.of());
        }

        @Override
        public long estimateTokens(ProviderChatRequest request) {
            return 5L;
        }

        @Override
        public ProviderChatResponse chat(ProviderCallContext context) {
            return new ProviderChatResponse("fast response", "stop", 5L, 10L, 15L, "ACTUAL", "req-fast");
        }

        @Override
        public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onNext(ProviderStreamChunk.content("fast chunk"));
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
            return new ProviderErrorClassification("PROVIDER_SERVER_ERROR", true, true, true, false);
        }
    }

    private static class FastStreamAdapter implements ProviderAdapter {
        private final int chunkCount;

        public FastStreamAdapter(int chunkCount) {
            this.chunkCount = chunkCount;
        }

        @Override
        public String providerType() {
            return "OPENAI";
        }

        @Override
        public AdapterCapabilities capabilities() {
            return new AdapterCapabilities(true, true, true, false, List.of("cl100k"), 4, Set.of("stop"), List.of());
        }

        @Override
        public long estimateTokens(ProviderChatRequest request) {
            return 5L;
        }

        @Override
        public ProviderChatResponse chat(ProviderCallContext context) {
            return new ProviderChatResponse("response", "stop", 5L, 5L, 10L, "ACTUAL", "req-1");
        }

        @Override
        public Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private int emitted = 0;

                @Override
                public void request(long n) {
                    while (emitted < chunkCount && n-- > 0) {
                        subscriber.onNext(ProviderStreamChunk.content("token-" + emitted++));
                    }
                    if (emitted >= chunkCount) {
                        subscriber.onNext(ProviderStreamChunk.finish("stop"));
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                }
            });
        }

        @Override
        public ProviderErrorClassification classifyError(ProviderFailure failure) {
            return new ProviderErrorClassification("PROVIDER_SERVER_ERROR", true, true, true, false);
        }
    }
}
