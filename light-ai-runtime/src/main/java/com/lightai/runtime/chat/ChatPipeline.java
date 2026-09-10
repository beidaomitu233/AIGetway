package com.lightai.runtime.chat;

import com.lightai.client.application.ApplicationModelConstraint;
import com.lightai.client.chat.ChatMessage;
import com.lightai.client.chat.ChatRequestValidator;
import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.error.UnifiedError;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.AdapterRegistryPort;
import com.lightai.runtime.ports.ApplicationQuotaPort;
import com.lightai.runtime.ports.CapacityPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.runtime.ports.RoutingPort;
import com.lightai.runtime.capacity.CapacityStore.CapacityLimitedException;
import com.lightai.runtime.capacity.QueueService;
import com.lightai.runtime.circuit.CircuitKey;
import com.lightai.runtime.circuit.CircuitPolicy;
import com.lightai.runtime.circuit.CircuitSnapshot;
import com.lightai.runtime.circuit.CircuitStateStore;
import com.lightai.runtime.settlement.PriceSnapshot;
import com.lightai.runtime.settlement.UsageSettlement;
import com.lightai.runtime.trace.TraceStore;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderCallContext;
import com.lightai.spi.provider.ProviderChatRequest;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderStreamChunk;
import com.lightai.spi.provider.ProviderTransportException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 统一调用管线（BE-027/028/029，4.7.1.3/4.7.1.4/4.7.1.5）：
 * 身份/校验 → Alias 解析（Alias 前失败无 Trace）→ 唯一 Trace 占位 → 固定快照 →
 * 能力/上下文过滤与排序 → 预占 → Attempt → Adapter 一次调用 → 结算 →
 * 恢复决策或最终化。流式首个业务块提交后禁止换路径；有 Trace 后所有失败最终化。
 * 路由/容量/凭证由端口解耦，BE-P04 内核交付后接线。
 */
public class ChatPipeline {

    private static final System.Logger log = System.getLogger(ChatPipeline.class.getName());

    private final ConfigSnapshotPort snapshotPort;
    private final AccessTokenPort.RuntimeConfigPort runtimeConfigPort;
    private final RoutingPort routingPort;
    private final CapacityPort capacityPort;
    private final ApplicationQuotaPort applicationQuotaPort;
    private final CircuitStateStore circuitStateStore;
    private final QueueService queueService;
    private final CredentialSecretPort credentialPort;
    private final AdapterRegistryPort adapterRegistry;
    private final TraceStore traceStore;
    private final ReliabilityBudgets.Port reliabilityPort;
    private final long totalTimeoutMs;

    public ChatPipeline(ConfigSnapshotPort snapshotPort, AccessTokenPort.RuntimeConfigPort runtimeConfigPort,
                        RoutingPort routingPort, CapacityPort capacityPort, CredentialSecretPort credentialPort,
                        AdapterRegistryPort adapterRegistry, TraceStore traceStore,
                        ReliabilityBudgets.Port reliabilityPort, long totalTimeoutMs) {
        this(snapshotPort, runtimeConfigPort, routingPort, capacityPort, null, credentialPort,
                null, adapterRegistry, traceStore, reliabilityPort, totalTimeoutMs);
    }

    public ChatPipeline(ConfigSnapshotPort snapshotPort, AccessTokenPort.RuntimeConfigPort runtimeConfigPort,
                        RoutingPort routingPort, CapacityPort capacityPort,
                        CircuitStateStore circuitStateStore, CredentialSecretPort credentialPort,
                        AdapterRegistryPort adapterRegistry, TraceStore traceStore,
                        ReliabilityBudgets.Port reliabilityPort, long totalTimeoutMs) {
        this(snapshotPort, runtimeConfigPort, routingPort, capacityPort, circuitStateStore,
                credentialPort, null, adapterRegistry, traceStore, reliabilityPort, totalTimeoutMs);
    }

    public ChatPipeline(ConfigSnapshotPort snapshotPort, AccessTokenPort.RuntimeConfigPort runtimeConfigPort,
                        RoutingPort routingPort, CapacityPort capacityPort,
                        CircuitStateStore circuitStateStore, CredentialSecretPort credentialPort,
                        QueueService queueService, AdapterRegistryPort adapterRegistry,
                        TraceStore traceStore, ReliabilityBudgets.Port reliabilityPort,
                        long totalTimeoutMs) {
        this(snapshotPort, runtimeConfigPort, routingPort, capacityPort, circuitStateStore,
                credentialPort, queueService, adapterRegistry, traceStore,
                ApplicationQuotaPort.unlimited(), reliabilityPort, totalTimeoutMs);
    }

    public ChatPipeline(ConfigSnapshotPort snapshotPort, AccessTokenPort.RuntimeConfigPort runtimeConfigPort,
                        RoutingPort routingPort, CapacityPort capacityPort,
                        CircuitStateStore circuitStateStore, CredentialSecretPort credentialPort,
                        QueueService queueService, AdapterRegistryPort adapterRegistry,
                        TraceStore traceStore, ApplicationQuotaPort applicationQuotaPort,
                        ReliabilityBudgets.Port reliabilityPort, long totalTimeoutMs) {
        this.snapshotPort = snapshotPort;
        this.runtimeConfigPort = runtimeConfigPort;
        this.routingPort = routingPort;
        this.capacityPort = capacityPort;
        this.applicationQuotaPort = applicationQuotaPort == null
                ? ApplicationQuotaPort.unlimited() : applicationQuotaPort;
        this.circuitStateStore = circuitStateStore;
        this.queueService = queueService;
        this.credentialPort = credentialPort;
        this.adapterRegistry = adapterRegistry;
        this.traceStore = traceStore;
        this.reliabilityPort = reliabilityPort;
        this.totalTimeoutMs = totalTimeoutMs;
    }

    /** 一次调用的上下文：身份校验后由入口层构造。 */
    public record ChatContext(AccessTokenPort.Principal principal, UnifiedChatRequest request,
                              CancellationSignal cancellation) {
    }

    /** 流式监听器：onCommit 之后才允许外发；onError 之后无任何块。 */
    public interface StreamListener {
        void onCommit();

        void onChunk(UnifiedChatChunk chunk);

        void onError(UnifiedError error);

        /** 仅在 Attempt 和 Trace 已成功最终化后触发一次。 */
        default void onComplete() {
        }
    }

    // ---------------------------------------------------------------- 同步

    public UnifiedChatResponse chat(ChatContext context) {
        long started = System.currentTimeMillis();
        ParsedRequest parsed = parse(context);
        List<CandidateView> candidates = route(parsed);
        String requestId = requestId(parsed.request());
        ApplicationQuotaPort.Reservation applicationReservation =
                reserveApplicationQuota(context.principal(), requestId, parsed, candidates);
        CancellationSignal signal = context.cancellation() != null
                ? context.cancellation() : new CancellationSignal("trace-pending");
        TraceStore.TraceHandle handle;
        try {
            handle = traceStore.create(requestId, parsed.alias(), context.principal().application());
        } catch (RuntimeException | Error failure) {
            applicationQuotaPort.release(applicationReservation, "TRACE_CREATE_FAILED");
            throw failure;
        }
        signal.bind(handle.traceId());

        try {
            ReliabilityBudgets budgets = reliabilityPort.budgets();
        int credentialIndex = 0;
        int candidateIndex = 0;
        int retries = 0;
        int failovers = 0;
        int fallbacks = 0;
        String lastError = ErrorCode.ALL_CANDIDATES_FAILED.name();

        while (true) {
            if (signal.cancelled()) {
                throw failFinal(handle, signal, false, ErrorCode.CLIENT_CANCELLED);
            }
            if (exceededTimeout(started)) {
                throw failFinal(handle, signal, false, ErrorCode.TOTAL_TIMEOUT);
            }
            CandidateView candidate = candidates.get(Math.min(candidateIndex, candidates.size() - 1));
            CapacityPort.Reservation reservation = null;
            CircuitAttempt circuitAttempt = null;
            String attemptId = null;
            boolean attemptFinished = false;
            try {
                CredentialSecretPort.ResolvedCredential credential =
                        credentialPort.resolve(candidate.poolId(), credentialIndex);
                long estimatedInput = estimatedInput(requestChars(parsed.request()));
                reservation = reserveCapacity(parsed, candidate, credential, estimatedInput,
                        traceId(handle), signal, started);
                circuitAttempt = acquireCircuit(parsed, candidate, credential);
                attemptId = traceStore.startAttempt(traceId(handle), candidate.candidateId(),
                        candidate.providerType(), candidate.modelId());
                ProviderAdapter adapter = requireAdapter(candidate);
                ProviderChatRequest adapterRequest = toAdapterRequest(candidate, parsed.request(),
                        estimatedInput, parsed.applicationMaxOutputTokens());
                ProviderCallContext callContext = callContext(candidate, adapterRequest, credential, started);
                ProviderChatResponse response;
                try {
                    response = adapter.chat(callContext);
                } catch (ProviderTransportException te) {
                    completeCircuit(circuitAttempt, false,
                            te.failure().httpStatus() != null && te.failure().httpStatus() == 429);
                    throw asLightAi(te, adapter);
                }
                completeCircuit(circuitAttempt, true, false);

                long estimatedOut = estimatedOutput(candidate);
                UsageSettlement.AttemptSettlement settlement = UsageSettlement.settle(
                        priceSnapshot(candidate), response.inputTokens(), response.outputTokens(),
                        estimatedInput, estimatedOut);
                String source = response.inputTokens() != null && response.outputTokens() != null
                        ? "ACTUAL" : "ESTIMATED";
                traceStore.finishAttempt(traceId(handle), attemptId, "SUCCEEDED", null,
                        settlement.usage().promptTokens(), settlement.usage().completionTokens(), source,
                        settlement.cost().amount().toPlainString(), settlement.cost().currency(),
                        settlement.cost().estimated());
                attemptFinished = true;
                capacityPort.settle(reservation.reservationId(),
                        settlement.usage().promptTokens(), settlement.usage().completionTokens());
                applicationQuotaPort.settle(applicationReservation,
                        applicationSettlement(parsed.aliasView().aliasId(), candidate, settlement));
                traceStore.finalizeTrace(traceId(handle), "SUCCEEDED");
                return new UnifiedChatResponse(
                        traceId(handle), "chat.completion", started / 1000, parsed.alias(),
                        List.of(new UnifiedChatResponse.Choice(0,
                                new UnifiedChatResponse.Message("assistant", response.content()),
                                response.finishReason())),
                        settlement.usage(),
                        new com.lightai.client.chat.ResponseTraceInfo(traceId(handle), candidate.providerType(),
                                candidate.modelId(), source, settlement.cost(), handle.snapshotNo()));
            } catch (LightAiException e) {
                if (attemptFinished) {
                    traceStore.finalizeTrace(traceId(handle), "FAILED");
                    throw e;
                }
                if (attemptId != null) {
                    traceStore.finishAttempt(traceId(handle), attemptId, "FAILED", e.code().name(),
                            0, 0, "ESTIMATED", null, null, false);
                }
                if (reservation != null) {
                    capacityPort.release(reservation.reservationId());
                }
                abandonCircuit(circuitAttempt);
                log.log(System.Logger.Level.INFO,
                        "尝试失败 trace_id={0} attempt_id={1} code={2} candidate={3} credential_index={4}"
                                + " 预算 retries={5}/{6} failovers={7}/{8} fallbacks={9}/{10}",
                        traceId(handle), attemptId, e.code().name(), candidate.candidateId(),
                        credentialIndex, retries, budgets.maxRetries(),
                        failovers, budgets.maxCredentialFailovers(),
                        fallbacks, budgets.maxFallbacks());
                lastError = e.code().name();
                RecoveryAction action = decide(e.code().name(), budgets, retries, failovers, fallbacks);
                switch (action) {
                    case RETRY -> retries++;
                    case CREDENTIAL_FAILOVER -> {
                        failovers++;
                        credentialIndex++;
                    }
                    case FALLBACK -> {
                        fallbacks++;
                        candidateIndex++;
                        if (candidateIndex >= candidates.size()) {
                            throw fail(handle, ErrorCode.ALL_CANDIDATES_FAILED, lastError);
                        }
                    }
                    case FAIL -> throw fail(handle, mapFinal(e.code().name()), lastError);
                }
            }
        }
        } catch (RuntimeException | Error failure) {
            applicationQuotaPort.release(applicationReservation, "REQUEST_FAILED");
            throw failure;
        }
    }

    // ---------------------------------------------------------------- 流式

    public void chatStream(ChatContext context, StreamListener listener) {
        long started = System.currentTimeMillis();
        ParsedRequest parsed = parse(context);
        List<CandidateView> candidates = route(parsed);
        String requestId = requestId(parsed.request());
        ApplicationQuotaPort.Reservation applicationReservation =
                reserveApplicationQuota(context.principal(), requestId, parsed, candidates);
        CancellationSignal signal = context.cancellation() != null
                ? context.cancellation() : new CancellationSignal("trace-pending");
        TraceStore.TraceHandle handle;
        try {
            handle = traceStore.create(requestId, parsed.alias(), context.principal().application());
        } catch (RuntimeException | Error failure) {
            applicationQuotaPort.release(applicationReservation, "TRACE_CREATE_FAILED");
            throw failure;
        }
        signal.bind(handle.traceId());

        try {
            ReliabilityBudgets budgets = reliabilityPort.budgets();
        AtomicSequencer sequence = new AtomicSequencer();
        int credentialIndex = 0;
        int candidateIndex = 0;
        int retries = 0;
        int failovers = 0;
        int fallbacks = 0;

        while (true) {
            if (signal.cancelled()) {
                throw failFinal(handle, signal, false, ErrorCode.CLIENT_CANCELLED);
            }
            if (exceededTimeout(started)) {
                failFinal(handle, signal, false, ErrorCode.TOTAL_TIMEOUT);
                applicationQuotaPort.release(applicationReservation, "TOTAL_TIMEOUT");
                return;
            }
            CandidateView candidate = candidates.get(Math.min(candidateIndex, candidates.size() - 1));
            CapacityPort.Reservation reservation = null;
            CircuitAttempt circuitAttempt = null;
            String attemptId = null;
            try {
                CredentialSecretPort.ResolvedCredential credential =
                        credentialPort.resolve(candidate.poolId(), credentialIndex);
                long estimatedInput = estimatedInput(requestChars(parsed.request()));
                reservation = reserveCapacity(parsed, candidate, credential, estimatedInput,
                        traceId(handle), signal, started);
                circuitAttempt = acquireCircuit(parsed, candidate, credential);
                attemptId = traceStore.startAttempt(traceId(handle), candidate.candidateId(),
                        candidate.providerType(), candidate.modelId());
                ProviderAdapter adapter = requireAdapter(candidate);
                ProviderChatRequest adapterRequest = toAdapterRequest(candidate, parsed.request(),
                        estimatedInput, parsed.applicationMaxOutputTokens());
                ProviderCallContext callContext = callContext(candidate, adapterRequest, credential, started);

                // include_usage 默认 false（4.7.1.4）；Trace 仍记录用量与成本
                boolean includeUsage = parsed.request().streamOptions() != null
                        && parsed.request().streamOptions().includeUsage();
                StreamAccumulator accumulator = new StreamAccumulator(handle, parsed.alias(), candidate,
                        adapterRequest, includeUsage, listener, sequence, signal, reservation, attemptId,
                        estimatedInput, estimatedOutput(candidate), circuitAttempt,
                        applicationReservation, parsed.aliasView().aliasId());
                adapter.streamChat(callContext).subscribe(accumulator.subscriber(adapter));
                return;
            } catch (LightAiException e) {
                if (attemptId != null && !traceStore.committed(traceId(handle))) {
                    traceStore.finishAttempt(traceId(handle), attemptId, "FAILED", e.code().name(),
                            0, 0, "ESTIMATED", null, null, false);
                }
                if (reservation != null) {
                    capacityPort.release(reservation.reservationId());
                }
                abandonCircuit(circuitAttempt);
                if (traceStore.committed(traceId(handle))) {
                    throw e;
                }
                RecoveryAction action = decide(e.code().name(), budgets, retries, failovers, fallbacks);
                switch (action) {
                    case RETRY -> retries++;
                    case CREDENTIAL_FAILOVER -> {
                        failovers++;
                        credentialIndex++;
                    }
                    case FALLBACK -> {
                        fallbacks++;
                        candidateIndex++;
                        if (candidateIndex >= candidates.size()) {
                            failFinal(handle, signal, false, ErrorCode.ALL_CANDIDATES_FAILED);
                            applicationQuotaPort.release(applicationReservation, "ALL_CANDIDATES_FAILED");
                            return;
                        }
                    }
                    case FAIL -> {
                        failFinal(handle, signal, false, mapFinal(e.code().name()));
                        applicationQuotaPort.release(applicationReservation, e.code().name());
                        return;
                    }
                }
            }
        }
        } catch (RuntimeException | Error failure) {
            applicationQuotaPort.release(applicationReservation, "REQUEST_FAILED");
            throw failure;
        }
    }

    /** 流式累积器：提交前缓冲，提交后顺序外发；异常桥接进恢复决策。 */
    private final class StreamAccumulator {

        private final TraceStore.TraceHandle handle;
        private final String alias;
        private final CandidateView candidate;
        private final ProviderChatRequest adapterRequest;
        private final boolean includeUsage;
        private final StreamListener listener;
        private final AtomicSequencer sequence;
        private final CancellationSignal signal;
        private final CapacityPort.Reservation reservation;
        private final String attemptId;
        private final Deque<ProviderStreamChunk> pending = new ArrayDeque<>();
        private boolean finishEmitted;
        private final long estimatedInputTokens;
        private final long estimatedOutputTokens;
        private final CircuitAttempt circuitAttempt;
        private final ApplicationQuotaPort.Reservation applicationReservation;
        private final String virtualModelId;
        private Long capturedInputTokens;
        private Long capturedOutputTokens;

        private StreamAccumulator(TraceStore.TraceHandle handle, String alias, CandidateView candidate,
                                  ProviderChatRequest adapterRequest, boolean includeUsage,
                                   StreamListener listener, AtomicSequencer sequence, CancellationSignal signal,
                                   CapacityPort.Reservation reservation, String attemptId,
                                   long estimatedInputTokens, long estimatedOutputTokens,
                                   CircuitAttempt circuitAttempt,
                                   ApplicationQuotaPort.Reservation applicationReservation,
                                   String virtualModelId) {
            this.handle = handle;
            this.alias = alias;
            this.candidate = candidate;
            this.adapterRequest = adapterRequest;
            this.includeUsage = includeUsage;
            this.listener = listener;
            this.sequence = sequence;
            this.signal = signal;
            this.reservation = reservation;
            this.attemptId = attemptId;
            this.estimatedInputTokens = estimatedInputTokens;
            this.estimatedOutputTokens = estimatedOutputTokens;
            this.circuitAttempt = circuitAttempt;
            this.applicationReservation = applicationReservation;
            this.virtualModelId = virtualModelId;
        }

        java.util.concurrent.Flow.Subscriber<ProviderStreamChunk> subscriber(ProviderAdapter adapter) {
            return new java.util.concurrent.Flow.Subscriber<>() {
                @Override
                public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ProviderStreamChunk chunk) {
                    if (signal.cancelled()) {
                        return;
                    }
                    if (chunk.type() == ProviderStreamChunk.Type.CONTENT
                            || chunk.type() == ProviderStreamChunk.Type.FINISH) {
                        pending.add(chunk);
                        if (!traceStore.committed(traceId(handle))) {
                            // 首个内容/正常结束块提交：固定最终输出路径，此后禁止换候选
                            traceStore.markCommitted(traceId(handle));
                            listener.onCommit();
                            emit(roleChunk());
                        }
                        flush();
                        return;
                    }
                    // USAGE 块：提交后按 include_usage 决定是否外发
                    if (traceStore.committed(traceId(handle))) {
                        emitUsage(chunk);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    if (traceStore.committed(traceId(handle))) {
                        // 提交后失败：STREAM_INTERRUPTED，错误事件后关闭，无 finish 无 DONE
                        LightAiException error = asLightAi(throwable, adapter);
                        completeCircuit(circuitAttempt, false,
                                error.code() == ErrorCode.PROVIDER_RATE_LIMITED);
                        traceStore.finishAttempt(traceId(handle), attemptId, "FAILED",
                                error.code().name(), 0, 0, "ESTIMATED", null, null, false);
                        signal.releaseOnce(() -> capacityPort.release(reservation.reservationId()));
                        applicationQuotaPort.release(applicationReservation, "STREAM_INTERRUPTED");
                        traceStore.finalizeTrace(traceId(handle), "STREAM_INTERRUPTED");
                        listener.onError(UnifiedError.builder(ErrorCode.STREAM_INTERRUPTED, "流式输出中断")
                                .traceId(traceId(handle)).build());
                        return;
                    }
                    // 提交前失败：抛回管线恢复循环
                    LightAiException error = asLightAi(throwable, adapter);
                    completeCircuit(circuitAttempt, false,
                            error.code() == ErrorCode.PROVIDER_RATE_LIMITED);
                    throw error;
                }

                @Override
                public void onComplete() {
                    boolean committed = traceStore.committed(traceId(handle));
                    if (!committed) {
                        // 无内容正常结束：提交并只发送 role 块与 finish 块
                        traceStore.markCommitted(traceId(handle));
                        listener.onCommit();
                        emit(roleChunk());
                        flush();
                        if (!finishEmitted) {
                            emit(finishChunk(ProviderChatResponse.FINISH_STOP));
                        }
                    }
                    // Attempt 终态与结算：Provider 流式 usage 缺失时按估算（4.7.1.4，Trace 仍记录用量与成本）
                    UsageSettlement.AttemptSettlement settlement = UsageSettlement.settle(
                            priceSnapshot(candidate), capturedInputTokens, capturedOutputTokens,
                            estimatedInputTokens, estimatedOutputTokens);
                    traceStore.finishAttempt(traceId(handle), attemptId, "SUCCEEDED", null,
                            settlement.usage().promptTokens(), settlement.usage().completionTokens(),
                            settlement.usage().source(), settlement.cost().amount().toPlainString(),
                            settlement.cost().currency(), settlement.cost().estimated());
                    signal.releaseOnce(() -> capacityPort.settle(reservation.reservationId(),
                            settlement.usage().promptTokens(), settlement.usage().completionTokens()));
                    try {
                        applicationQuotaPort.settle(applicationReservation,
                                applicationSettlement(virtualModelId, candidate, settlement));
                    } catch (RuntimeException settlementFailure) {
                        applicationQuotaPort.release(applicationReservation,
                                "SETTLEMENT_FAILED");
                        traceStore.finalizeTrace(traceId(handle), "STREAM_INTERRUPTED");
                        listener.onError(UnifiedError.builder(
                                ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "应用用量结算失败")
                                .traceId(traceId(handle)).build());
                        return;
                    }
                    completeCircuit(circuitAttempt, true, false);
                    traceStore.finalizeTrace(traceId(handle), "SUCCEEDED");
                    listener.onComplete();
                }

                private void flush() {
                    while (!pending.isEmpty()) {
                        ProviderStreamChunk chunk = pending.poll();
                        switch (chunk.type()) {
                            case CONTENT -> emit(contentChunk(chunk));
                            case FINISH -> {
                                emit(finishChunk(chunk.finishReason()));
                                finishEmitted = true;
                            }
                            case USAGE -> emitUsage(chunk);
                        }
                    }
                }

                private void emit(UnifiedChatChunk chunk) {
                    listener.onChunk(chunk);
                }

                private void emitUsage(ProviderStreamChunk chunk) {
                    // 无论 includeUsage 均捕获真实用量供 Attempt 结算；外发仍按 includeUsage 决定
                    if (chunk.inputTokens() != null) {
                        capturedInputTokens = chunk.inputTokens();
                    }
                    if (chunk.outputTokens() != null) {
                        capturedOutputTokens = chunk.outputTokens();
                    }
                    if (!includeUsage) {
                        return;
                    }
                    long input = chunk.inputTokens() == null ? 0 : chunk.inputTokens();
                    long output = chunk.outputTokens() == null ? 0 : chunk.outputTokens();
                    UsageSettlement.AttemptSettlement settlement = UsageSettlement.settle(
                            priceSnapshot(candidate), input, output, 0, 0);
                    listener.onChunk(new UnifiedChatChunk(traceId(handle), "chat.completion.chunk",
                            System.currentTimeMillis() / 1000, alias, List.of(), settlement.usage(),
                            new UnifiedChatChunk.ChunkTraceInfo(traceId(handle), sequence.next(),
                                    candidate.providerType(), candidate.modelId(), settlement.cost())));
                }

                private UnifiedChatChunk roleChunk() {
                    return new UnifiedChatChunk(traceId(handle), "chat.completion.chunk",
                            System.currentTimeMillis() / 1000, alias,
                            List.of(new UnifiedChatChunk.ChunkChoice(0,
                                    new UnifiedChatChunk.Delta("assistant", null), null)), null,
                            new UnifiedChatChunk.ChunkTraceInfo(traceId(handle), sequence.next(), null, null, null));
                }

                private UnifiedChatChunk contentChunk(ProviderStreamChunk chunk) {
                    return new UnifiedChatChunk(traceId(handle), "chat.completion.chunk",
                            System.currentTimeMillis() / 1000, alias,
                            List.of(new UnifiedChatChunk.ChunkChoice(0,
                                    new UnifiedChatChunk.Delta(null, chunk.content()), null)), null,
                            new UnifiedChatChunk.ChunkTraceInfo(traceId(handle), sequence.next(), null, null, null));
                }

                private UnifiedChatChunk finishChunk(String finishReason) {
                    return new UnifiedChatChunk(traceId(handle), "chat.completion.chunk",
                            System.currentTimeMillis() / 1000, alias,
                            List.of(new UnifiedChatChunk.ChunkChoice(0,
                                    new UnifiedChatChunk.Delta(null, null), finishReason)), null,
                            new UnifiedChatChunk.ChunkTraceInfo(traceId(handle), sequence.next(), null, null, null));
                }
            };
        }
    }

    // ---------------------------------------------------------------- 共用

    private String requestId(UnifiedChatRequest request) {
        return request.traceId() == null || request.traceId().isBlank()
                ? UUID.randomUUID().toString() : request.traceId();
    }

    private ApplicationQuotaPort.Reservation reserveApplicationQuota(
            AccessTokenPort.Principal principal, String requestId, ParsedRequest parsed,
            List<CandidateView> candidates) {
        long input = estimatedInput(requestChars(parsed.request()));
        long maxTotal = input;
        List<ApplicationQuotaPort.AmountEstimate> estimates = new ArrayList<>();
        for (CandidateView candidate : candidates) {
            long output = resolveMaxTokens(candidate, parsed.request(), input,
                    parsed.applicationMaxOutputTokens());
            maxTotal = Math.max(maxTotal, input + output);
            UsageSettlement.AttemptSettlement estimate = UsageSettlement.settle(
                    priceSnapshot(candidate), null, null, input, output);
            estimates.add(new ApplicationQuotaPort.AmountEstimate(
                    estimate.cost().currency(), estimate.cost().amount()));
        }
        return applicationQuotaPort.reserve(principal, requestId, maxTotal, estimates);
    }

    private ApplicationQuotaPort.Settlement applicationSettlement(
            String virtualModelId, CandidateView candidate,
            UsageSettlement.AttemptSettlement settlement) {
        PriceSnapshot price = priceSnapshot(candidate);
        return new ApplicationQuotaPort.Settlement(
                settlement.usage().promptTokens(), settlement.usage().completionTokens(),
                settlement.cost().amount(), settlement.cost().currency(),
                settlement.usage().source(), virtualModelId, candidate.modelPk(),
                price.inputPrice().toPlainString(), price.outputPrice().toPlainString(),
                price.priceUnit());
    }

    private ParsedRequest parse(ChatContext context) {
        ChatRequestValidator.validate(context.request(), true);
        String alias = context.request().model();
        if (alias == null || alias.isBlank()) {
            alias = runtimeConfigPort.defaultAliasId()
                    .orElseThrow(() -> new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                            "model 缺省且未配置默认 Alias", "model"));
        }
        String resolvedAlias = alias;
        if (context.principal() != null && !context.principal().aliasAllowed(resolvedAlias)) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "应用未授权访问该模型");
        }
        ConfigSnapshotPort.ActiveSnapshot snapshot = snapshotPort.active();
        AliasView aliasView = snapshot.alias(resolvedAlias)
                .orElseThrow(() -> ConfigSnapshotPort.aliasNotFound(resolvedAlias));
        if (!aliasView.enabled()) {
            throw ConfigSnapshotPort.aliasDisabled(resolvedAlias);
        }
        ApplicationModelConstraint constraint = enforceApplicationModelConstraint(
                context.principal(), resolvedAlias, context.request());
        return new ParsedRequest(resolvedAlias, context.request(), snapshot, aliasView,
                constraint == null ? null : constraint.maxOutputTokens());
    }

    /**
     * PRD 9.2.5：应用为虚拟模型配置的请求参数上限在路由前生效。
     * 显式越界参数直接以统一错误拒绝，不静默删除参数后继续调用。
     */
    private static ApplicationModelConstraint enforceApplicationModelConstraint(
            AccessTokenPort.Principal principal, String alias, UnifiedChatRequest request) {
        if (principal == null) {
            return null;
        }
        ApplicationModelConstraint constraint = principal.constraintFor(alias);
        if (constraint == null || constraint.isEmpty()) {
            return null;
        }
        List<FieldIssue> issues = new ArrayList<>();
        if (Boolean.FALSE.equals(constraint.streamAllowed()) && request.stream()) {
            issues.add(new FieldIssue("stream", "APPLICATION_LIMIT",
                    "应用未允许该模型使用流式调用"));
        }
        if (constraint.maxOutputTokens() != null && request.maxTokens() != null
                && request.maxTokens() > constraint.maxOutputTokens()) {
            issues.add(new FieldIssue("max_tokens", "APPLICATION_LIMIT",
                    "max_tokens 超过应用对该模型的上限 " + constraint.maxOutputTokens()));
        }
        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.APPLICATION_MODEL_CONSTRAINT_VIOLATED,
                    "请求参数超过应用模型策略上限: " + alias, issues);
        }
        return constraint;
    }

    private List<CandidateView> route(ParsedRequest parsed) {
        AliasView aliasView = parsed.aliasView();
        if (aliasView.enabledCandidates().isEmpty()) {
            throw new LightAiException(ErrorCode.MODEL_CAPABILITY_NOT_SUPPORTED, "Alias 没有启用候选");
        }
        RoutingPort.RoutingResult result = routingPort.order(aliasView, parsed.request(),
                estimatedInput(requestChars(parsed.request())));
        if (result.candidates().isEmpty()) {
            throw result.rejection();
        }
        return result.candidates();
    }

    private CapacityPort.Reservation reserveCapacity(
            ParsedRequest parsed, CandidateView candidate,
            CredentialSecretPort.ResolvedCredential credential, long estimatedInput,
            String traceId, CancellationSignal signal, long started) {
        long maxTokens = resolveMaxTokens(candidate, parsed.request(), estimatedInput,
                parsed.applicationMaxOutputTokens());
        try {
            return reserveOnce(parsed, candidate, credential, estimatedInput, maxTokens);
        } catch (CapacityLimitedException limited) {
            String scopeId = capacityScopeId(limited.scopeType(), parsed, candidate, credential);
            ConfigSnapshotPort.QueuePolicy policy = parsed.snapshot()
                    .queuePolicy(limited.scopeType(), scopeId);
            if (queueService == null || !policy.queues()) throw limited;
            return awaitCapacity(parsed, candidate, credential, estimatedInput, maxTokens,
                    traceId, signal, started, policy);
        }
    }

    private CapacityPort.Reservation reserveOnce(
            ParsedRequest parsed, CandidateView candidate,
            CredentialSecretPort.ResolvedCredential credential, long estimatedInput, long maxTokens) {
        return capacityPort.reserve(parsed.aliasView().aliasId(), candidate.modelPk(),
                credential.credentialId(), estimatedInput, maxTokens,
                parsed.snapshot().capacityLimit("MODEL_ALIAS", parsed.aliasView().aliasId()),
                parsed.snapshot().capacityLimit("PROVIDER_MODEL", candidate.modelPk()),
                parsed.snapshot().capacityLimit("CREDENTIAL", credential.credentialId()));
    }

    private CapacityPort.Reservation awaitCapacity(
            ParsedRequest parsed, CandidateView candidate,
            CredentialSecretPort.ResolvedCredential credential, long estimatedInput, long maxTokens,
            String traceId, CancellationSignal signal, long started,
            ConfigSnapshotPort.QueuePolicy policy) {
        UUID aliasId = uuid(parsed.aliasView().aliasId());
        if (aliasId == null) {
            throw new LightAiException(ErrorCode.CAPACITY_LIMITED,
                    "Alias 标识无效，无法进入共享队列");
        }
        long deadline = Math.min(started + totalTimeoutMs,
                System.currentTimeMillis() + policy.timeoutMs());
        UUID traceUuid = uuid(traceId);
        if (traceUuid == null) {
            traceUuid = UUID.nameUUIDFromBytes(traceId.getBytes(StandardCharsets.UTF_8));
        }
        QueueService.QueueTicket ticket = queueService.enqueue(
                aliasId, traceUuid, deadline, Instant.now(), policy.maxSize());
        boolean completed = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (signal.cancelled()) {
                    throw new LightAiException(ErrorCode.CLIENT_CANCELLED, "排队等待已取消");
                }
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw new LightAiException(ErrorCode.CLIENT_CANCELLED, "排队等待线程已中断");
                }
                if (queueService.isHead(ticket.ticketId(), Instant.now())) {
                    try {
                        CapacityPort.Reservation reservation = reserveOnce(
                                parsed, candidate, credential, estimatedInput, maxTokens);
                        queueService.complete(ticket.ticketId());
                        completed = true;
                        return reservation;
                    } catch (CapacityLimitedException stillLimited) {
                        // 容量仍不足，保持队首并短暂退避。
                    }
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            throw new LightAiException(ErrorCode.QUEUE_TIMEOUT, "容量排队等待超时");
        } finally {
            if (!completed) queueService.cancel(ticket.ticketId());
        }
    }

    private static String capacityScopeId(
            String scopeType, ParsedRequest parsed, CandidateView candidate,
            CredentialSecretPort.ResolvedCredential credential) {
        if (scopeType == null) return null;
        return switch (scopeType.toUpperCase(java.util.Locale.ROOT)) {
            case "ALIAS", "MODEL_ALIAS" -> parsed.aliasView().aliasId();
            case "PROVIDER_MODEL" -> candidate.modelPk();
            case "CREDENTIAL" -> credential.credentialId();
            default -> null;
        };
    }

    private CircuitAttempt acquireCircuit(ParsedRequest parsed, CandidateView candidate,
                                          CredentialSecretPort.ResolvedCredential credential) {
        if (circuitStateStore == null) return null;
        CircuitKey key = new CircuitKey(uuid(candidate.modelPk()), uuid(credential.credentialId()));
        if (key.providerModelId() == null || key.credentialId() == null) return null;
        CircuitPolicy policy = parsed.snapshot().circuitPolicy(parsed.aliasView().aliasId());
        CircuitSnapshot snapshot = circuitStateStore.snapshot(key, policy, Instant.now());
        if (CircuitSnapshot.STATE_OPEN.equals(snapshot.state())) {
            throw new LightAiException(ErrorCode.CIRCUIT_OPEN,
                    "当前 Provider Model 与 Credential 路径已熔断");
        }
        CircuitStateStore.ProbeSlot slot = null;
        if (CircuitSnapshot.STATE_HALF_OPEN.equals(snapshot.state())) {
            slot = circuitStateStore.tryAcquireProbe(key, policy, Instant.now())
                    .orElseThrow(() -> new LightAiException(
                            ErrorCode.CIRCUIT_OPEN, "熔断探测名额已用完"));
        }
        return new CircuitAttempt(key, policy, slot, new AtomicBoolean());
    }

    private void completeCircuit(CircuitAttempt attempt, boolean success, boolean throttled) {
        if (attempt == null || !attempt.completed().compareAndSet(false, true)) return;
        try {
            circuitStateStore.recordResult(attempt.key(), attempt.policy(), success, throttled, Instant.now());
        } finally {
            if (attempt.slot() != null) {
                circuitStateStore.releaseProbe(attempt.slot(), attempt.key(), success, Instant.now());
            }
        }
    }

    private void abandonCircuit(CircuitAttempt attempt) {
        if (attempt == null || !attempt.completed().compareAndSet(false, true)) return;
        if (attempt.slot() != null) {
            circuitStateStore.releaseProbe(attempt.slot(), attempt.key(), false, Instant.now());
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ProviderAdapter requireAdapter(CandidateView candidate) {
        return adapterRegistry.adapter(candidate.providerType())
                .orElseThrow(() -> AdapterRegistryPort.adapterNotFound(candidate.providerType()));
    }

    private ProviderCallContext callContext(CandidateView candidate, ProviderChatRequest request,
                                            CredentialSecretPort.ResolvedCredential credential, long started) {
        String baseUrl = candidate.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "候选 " + candidate.candidateId() + " 的 Provider 未配置 base_url，拒绝外呼");
        }
        return new ProviderCallContext(
                new com.lightai.spi.provider.ProviderConfigView(candidate.providerType(),
                        baseUrl, candidate.proxyUrl(),
                        candidate.connectTimeoutMs() > 0 ? candidate.connectTimeoutMs() : 3000,
                        candidate.readTimeoutMs() > 0 ? candidate.readTimeoutMs() : 120000,
                        candidate.defaultHeaders()),
                request, credential.secretHandle(),
                Instant.now().plusMillis(Math.max(1, totalTimeoutMs)));
    }

    private ProviderChatRequest toAdapterRequest(CandidateView candidate, UnifiedChatRequest request,
                                                 long estimatedInput, Integer applicationMaxOutputTokens) {
        Long resolvedMaxTokens = resolveMaxTokens(candidate, request, estimatedInput,
                applicationMaxOutputTokens);
        BigDecimal temperature = request.temperature() != null ? request.temperature()
                : candidate.defaultTemperature();
        BigDecimal topP = request.topP() != null ? request.topP() : candidate.defaultTopP();
        List<ProviderChatRequest.ChatTurn> turns = new ArrayList<>();
        String system = null;
        for (ChatMessage message : request.messages()) {
            if ("system".equals(message.role())) {
                system = message.content();
                continue;
            }
            turns.add(new ProviderChatRequest.ChatTurn(message.role(), message.content()));
        }
        return new ProviderChatRequest(candidate.modelId(), system, turns, resolvedMaxTokens,
                temperature, topP, request.stop(), filteredOptions(candidate, request));
    }

    private Long resolveMaxTokens(CandidateView candidate, UnifiedChatRequest request,
                                  long estimatedInput, Integer applicationMaxOutputTokens) {
        if (request.maxTokens() != null) {
            return request.maxTokens().longValue();
        }
        long resolved = candidate.defaultMaxTokens() != null ? candidate.defaultMaxTokens()
                : (candidate.maxOutputTokens() != null ? candidate.maxOutputTokens() : 1024L);
        if (candidate.maxOutputTokens() != null) {
            resolved = Math.min(resolved, candidate.maxOutputTokens());
        }
        if (applicationMaxOutputTokens != null) {
            resolved = Math.min(resolved, applicationMaxOutputTokens);
        }
        if (candidate.contextWindow() != null) {
            resolved = Math.min(resolved, Math.max(1, candidate.contextWindow() - estimatedInput));
        }
        return Math.max(1, resolved);
    }

    private Map<String, Object> filteredOptions(CandidateView candidate, UnifiedChatRequest request) {
        Map<String, Object> filtered = new java.util.HashMap<>();
        String prefix = candidate.providerType().toLowerCase() + ".";
        Map<String, Object> options = request.providerOptions();
        if (options != null) {
            options.forEach((key, value) -> {
                if (key.startsWith(prefix)) {
                    filtered.put(key.substring(prefix.length()), value);
                }
            });
        }
        return filtered;
    }

    private PriceSnapshot priceSnapshot(CandidateView candidate) {
        return new PriceSnapshot(candidate.modelId(),
                candidate.inputPrice() == null ? BigDecimal.ZERO : new BigDecimal(candidate.inputPrice()),
                candidate.outputPrice() == null ? BigDecimal.ZERO : new BigDecimal(candidate.outputPrice()),
                candidate.priceUnit(), candidate.currency());
    }

    private RecoveryAction decide(String errorCode, ReliabilityBudgets budgets,
                                  int retries, int failovers, int fallbacks) {
        // 429 优先换 Credential，再 Fallback，最后才允许预算内 Retry（4.3.4.4）
        if (failovers < budgets.maxCredentialFailovers()
                && !"PROVIDER_REQUEST_REJECTED".equals(errorCode)
                && !"PROVIDER_MODEL_NOT_FOUND".equals(errorCode)
                && !"TOTAL_TIMEOUT".equals(errorCode)) {
            return RecoveryAction.CREDENTIAL_FAILOVER;
        }
        if (fallbacks < budgets.maxFallbacks()
                && !"PROVIDER_REQUEST_REJECTED".equals(errorCode)
                && !"TOTAL_TIMEOUT".equals(errorCode)) {
            return RecoveryAction.FALLBACK;
        }
        boolean retryable = switch (errorCode) {
            case "NETWORK_ERROR", "CONNECT_TIMEOUT", "FIRST_TOKEN_TIMEOUT", "PROVIDER_BAD_RESPONSE",
                    "PROVIDER_SERVER_ERROR" -> true;
            default -> false;
        };
        if (retryable && retries < budgets.maxRetries()) {
            return RecoveryAction.RETRY;
        }
        return RecoveryAction.FAIL;
    }

    private LightAiException fail(TraceStore.TraceHandle handle, ErrorCode code, String lastError) {
        traceStore.finalizeTrace(traceId(handle),
                code == ErrorCode.CLIENT_CANCELLED ? "CANCELLED" : "FAILED");
        return new LightAiException(code,
                code == ErrorCode.CLIENT_CANCELLED ? "客户端已取消" : "所有候选尝试均失败",
                null, traceId(handle), null, null, null);
    }

    private LightAiException failFinal(TraceStore.TraceHandle handle, CancellationSignal signal,
                                       boolean committed, ErrorCode code) {
        if (code == ErrorCode.CLIENT_CANCELLED) {
            traceStore.finalizeTrace(traceId(handle), "CANCELLED");
        } else if (code == ErrorCode.STREAM_INTERRUPTED) {
            traceStore.finalizeTrace(traceId(handle), "STREAM_INTERRUPTED");
        } else {
            traceStore.finalizeTrace(traceId(handle), "FAILED");
        }
        return new LightAiException(code,
                code == ErrorCode.CLIENT_CANCELLED ? "客户端已取消" : "所有候选尝试均失败",
                null, traceId(handle), null, null, null);
    }

    private ErrorCode mapFinal(String errorCode) {
        return switch (errorCode) {
            case "PROVIDER_AUTH_FAILED" -> ErrorCode.ALL_CANDIDATES_FAILED;
            case "PROVIDER_MODEL_NOT_FOUND" -> ErrorCode.PROVIDER_MODEL_NOT_FOUND;
            case "PROVIDER_REQUEST_REJECTED" -> ErrorCode.PROVIDER_REQUEST_REJECTED;
            default -> ErrorCode.ALL_CANDIDATES_FAILED;
        };
    }

    private LightAiException asLightAi(Throwable throwable, ProviderAdapter adapter) {
        if (throwable instanceof LightAiException e) {
            return e;
        }
        if (throwable instanceof ProviderTransportException e) {
            com.lightai.spi.provider.ProviderErrorClassification classification =
                    adapter.classifyError(e.failure());
            return new LightAiException(ErrorCode.valueOf(classification.unifiedCode()),
                    "Provider 调用失败: " + classification.unifiedCode());
        }
        return new LightAiException(ErrorCode.PROVIDER_BAD_RESPONSE, "Provider 响应无法解析");
    }

    private long estimatedInput(long chars) {
        return Math.max(1, chars / 4);
    }

    private long estimatedOutput(CandidateView candidate) {
        return candidate.maxOutputTokens() != null ? Math.min(candidate.maxOutputTokens(), 1024L) : 1024L;
    }

    private long requestChars(UnifiedChatRequest request) {
        long chars = 0;
        for (ChatMessage message : request.messages()) {
            chars += message.content() == null ? 0 : message.content().length();
        }
        return chars;
    }

    private boolean exceededTimeout(long started) {
        return System.currentTimeMillis() - started > totalTimeoutMs;
    }

    private static String traceId(TraceStore.TraceHandle handle) {
        return handle.traceId();
    }

    private enum RecoveryAction {
        RETRY,
        CREDENTIAL_FAILOVER,
        FALLBACK,
        FAIL
    }

    private record ParsedRequest(String alias, UnifiedChatRequest request,
                                 ConfigSnapshotPort.ActiveSnapshot snapshot,
                                 AliasView aliasView,
                                 Integer applicationMaxOutputTokens) {
    }

    private record CircuitAttempt(CircuitKey key, CircuitPolicy policy,
                                  CircuitStateStore.ProbeSlot slot, AtomicBoolean completed) {
    }

    /** sequence 计数器：role 块 0 起连续递增。 */
    static final class AtomicSequencer {
        private long value;

        long next() {
            return value++;
        }
    }
}
