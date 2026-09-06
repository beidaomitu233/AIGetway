package com.lightai.runtime.chat;

import com.lightai.client.chat.ChatMessage;
import com.lightai.client.chat.ChatRequestValidator;
import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.error.UnifiedError;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.AdapterRegistryPort;
import com.lightai.runtime.ports.CapacityPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.runtime.ports.RoutingPort;
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
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一调用管线（BE-027/028/029，4.7.1.3/4.7.1.4/4.7.1.5）：
 * 身份/校验 → Alias 解析（Alias 前失败无 Trace）→ 唯一 Trace 占位 → 固定快照 →
 * 能力/上下文过滤与排序 → 预占 → Attempt → Adapter 一次调用 → 结算 →
 * 恢复决策或最终化。流式首个业务块提交后禁止换路径；有 Trace 后所有失败最终化。
 * 路由/容量/凭证由端口解耦，BE-P04 内核交付后接线。
 */
public class ChatPipeline {

    private final ConfigSnapshotPort snapshotPort;
    private final AccessTokenPort.RuntimeConfigPort runtimeConfigPort;
    private final RoutingPort routingPort;
    private final CapacityPort capacityPort;
    private final CredentialSecretPort credentialPort;
    private final AdapterRegistryPort adapterRegistry;
    private final TraceStore traceStore;
    private final ReliabilityBudgets.Port reliabilityPort;
    private final long totalTimeoutMs;

    public ChatPipeline(ConfigSnapshotPort snapshotPort, AccessTokenPort.RuntimeConfigPort runtimeConfigPort,
                        RoutingPort routingPort, CapacityPort capacityPort, CredentialSecretPort credentialPort,
                        AdapterRegistryPort adapterRegistry, TraceStore traceStore,
                        ReliabilityBudgets.Port reliabilityPort, long totalTimeoutMs) {
        this.snapshotPort = snapshotPort;
        this.runtimeConfigPort = runtimeConfigPort;
        this.routingPort = routingPort;
        this.capacityPort = capacityPort;
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
    }

    // ---------------------------------------------------------------- 同步

    public UnifiedChatResponse chat(ChatContext context) {
        long started = System.currentTimeMillis();
        ParsedRequest parsed = parse(context);
        CancellationSignal signal = context.cancellation() != null
                ? context.cancellation() : new CancellationSignal("trace-pending");
        TraceStore.TraceHandle handle = traceStore.create(parsed.request().traceId(), parsed.alias(), null);
        signal.bind(handle.traceId());

        List<CandidateView> candidates = route(parsed);
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
            String attemptId = null;
            try {
                CredentialSecretPort.ResolvedCredential credential =
                        credentialPort.resolve(candidate.poolId(), credentialIndex);
                long estimatedInput = estimatedInput(requestChars(parsed.request()));
                reservation = capacityPort.reserve(parsed.alias(), candidate.modelId(),
                        credential.credentialId(), estimatedInput);
                attemptId = traceStore.startAttempt(traceId(handle), candidate.candidateId(),
                        candidate.providerType(), candidate.modelId());
                ProviderAdapter adapter = requireAdapter(candidate);
                ProviderChatRequest adapterRequest = toAdapterRequest(candidate, parsed.request(), estimatedInput);
                ProviderCallContext callContext = callContext(candidate, adapterRequest, credential, started);
                ProviderChatResponse response;
                try {
                    response = adapter.chat(callContext);
                } catch (ProviderTransportException te) {
                    throw asLightAi(te, adapter);
                }

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
                capacityPort.settle(reservation.reservationId(),
                        settlement.usage().promptTokens(), settlement.usage().completionTokens());
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
                if (attemptId != null) {
                    traceStore.finishAttempt(traceId(handle), attemptId, "FAILED", e.code().name(),
                            0, 0, "ESTIMATED", null, null, false);
                }
                if (reservation != null) {
                    capacityPort.release(reservation.reservationId());
                }
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
    }

    // ---------------------------------------------------------------- 流式

    public void chatStream(ChatContext context, StreamListener listener) {
        long started = System.currentTimeMillis();
        ParsedRequest parsed = parse(context);
        CancellationSignal signal = context.cancellation() != null
                ? context.cancellation() : new CancellationSignal("trace-pending");
        TraceStore.TraceHandle handle = traceStore.create(parsed.request().traceId(), parsed.alias(), null);
        signal.bind(handle.traceId());

        List<CandidateView> candidates = route(parsed);
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
                return;
            }
            CandidateView candidate = candidates.get(Math.min(candidateIndex, candidates.size() - 1));
            CapacityPort.Reservation reservation = null;
            String attemptId = null;
            try {
                CredentialSecretPort.ResolvedCredential credential =
                        credentialPort.resolve(candidate.poolId(), credentialIndex);
                long estimatedInput = estimatedInput(requestChars(parsed.request()));
                reservation = capacityPort.reserve(parsed.alias(), candidate.modelId(),
                        credential.credentialId(), estimatedInput);
                attemptId = traceStore.startAttempt(traceId(handle), candidate.candidateId(),
                        candidate.providerType(), candidate.modelId());
                ProviderAdapter adapter = requireAdapter(candidate);
                ProviderChatRequest adapterRequest = toAdapterRequest(candidate, parsed.request(), estimatedInput);
                ProviderCallContext callContext = callContext(candidate, adapterRequest, credential, started);

                // include_usage 默认 false（4.7.1.4）；Trace 仍记录用量与成本
                boolean includeUsage = parsed.request().streamOptions() != null
                        && parsed.request().streamOptions().includeUsage();
                StreamAccumulator accumulator = new StreamAccumulator(handle, parsed.alias(), candidate,
                        adapterRequest, includeUsage, listener, sequence, signal, reservation, attemptId);
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
                            return;
                        }
                    }
                    case FAIL -> {
                        failFinal(handle, signal, false, mapFinal(e.code().name()));
                        return;
                    }
                }
            }
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

        private StreamAccumulator(TraceStore.TraceHandle handle, String alias, CandidateView candidate,
                                  ProviderChatRequest adapterRequest, boolean includeUsage,
                                  StreamListener listener, AtomicSequencer sequence, CancellationSignal signal,
                                  CapacityPort.Reservation reservation, String attemptId) {
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
                        traceStore.finishAttempt(traceId(handle), attemptId, "FAILED",
                                error.code().name(), 0, 0, "ESTIMATED", null, null, false);
                        signal.releaseOnce(() -> capacityPort.release(reservation.reservationId()));
                        traceStore.finalizeTrace(traceId(handle), "STREAM_INTERRUPTED");
                        listener.onError(UnifiedError.builder(ErrorCode.STREAM_INTERRUPTED, "流式输出中断")
                                .traceId(traceId(handle)).build());
                        return;
                    }
                    // 提交前失败：抛回管线恢复循环
                    throw asLightAi(throwable, adapter);
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
                    signal.releaseOnce(() -> capacityPort.settle(reservation.reservationId(), 0, 0));
                    traceStore.finalizeTrace(traceId(handle), "SUCCEEDED");
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

    private ParsedRequest parse(ChatContext context) {
        ChatRequestValidator.validate(context.request(), true);
        String alias = context.request().model();
        if (alias == null || alias.isBlank()) {
            alias = runtimeConfigPort.defaultAliasId()
                    .orElseThrow(() -> new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                            "model 缺省且未配置默认 Alias", "model"));
        }
        return new ParsedRequest(alias, context.request());
    }

    private List<CandidateView> route(ParsedRequest parsed) {
        ConfigSnapshotPort.ActiveSnapshot snapshot = snapshotPort.active();
        AliasView aliasView = snapshot.alias(parsed.alias())
                .orElseThrow(() -> ConfigSnapshotPort.aliasNotFound(parsed.alias()));
        if (!aliasView.enabled()) {
            throw ConfigSnapshotPort.aliasDisabled(parsed.alias());
        }
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

    private ProviderAdapter requireAdapter(CandidateView candidate) {
        return adapterRegistry.adapter(candidate.providerType())
                .orElseThrow(() -> AdapterRegistryPort.adapterNotFound(candidate.providerType()));
    }

    private ProviderCallContext callContext(CandidateView candidate, ProviderChatRequest request,
                                            CredentialSecretPort.ResolvedCredential credential, long started) {
        return new ProviderCallContext(
                new com.lightai.spi.provider.ProviderConfigView(candidate.providerType(),
                        "https://adapter.invalid/", null, 3000, 120000, Map.of()),
                request, credential.secretHandle(),
                Instant.now().plusMillis(Math.max(1, totalTimeoutMs)));
    }

    private ProviderChatRequest toAdapterRequest(CandidateView candidate, UnifiedChatRequest request,
                                                 long estimatedInput) {
        Long resolvedMaxTokens = resolveMaxTokens(candidate, request, estimatedInput);
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

    private Long resolveMaxTokens(CandidateView candidate, UnifiedChatRequest request, long estimatedInput) {
        if (request.maxTokens() != null) {
            return request.maxTokens().longValue();
        }
        long resolved = candidate.defaultMaxTokens() != null ? candidate.defaultMaxTokens()
                : (candidate.maxOutputTokens() != null ? candidate.maxOutputTokens() : 1024L);
        if (candidate.maxOutputTokens() != null) {
            resolved = Math.min(resolved, candidate.maxOutputTokens());
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

    private record ParsedRequest(String alias, UnifiedChatRequest request) {
    }

    /** sequence 计数器：role 块 0 起连续递增。 */
    static final class AtomicSequencer {
        private long value;

        long next() {
            return value++;
        }
    }
}
