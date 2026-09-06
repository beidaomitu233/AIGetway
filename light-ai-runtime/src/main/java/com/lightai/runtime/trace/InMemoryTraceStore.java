package com.lightai.runtime.trace;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存 TraceStore（BE-P06 契约夹具）：唯一 trace_id 占位、Attempt 时间线、
 * 提交标记与一次最终化。真实 Trace/Attempt 落库由 BE-P06 与 DB-P03 提供。
 */
public final class InMemoryTraceStore implements TraceStore {

    private final Map<String, TraceRow> traces = new ConcurrentHashMap<>();
    private final Set<String> clientTraceIds = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();

    @Override
    public TraceHandle create(String clientTraceIdOrNull, String model, String application) {
        synchronized (lock) {
            if (clientTraceIdOrNull != null && !clientTraceIds.add(clientTraceIdOrNull)) {
                throw new LightAiException(ErrorCode.TRACE_ID_CONFLICT, "接入方提供的 trace_id 已被使用");
            }
            String traceId = clientTraceIdOrNull != null ? clientTraceIdOrNull
                    : UUID.randomUUID().toString();
            traces.put(traceId, new TraceRow(traceId, model, application));
            return new TraceHandle(traceId, 0);
        }
    }

    @Override
    public String startAttempt(String traceId, String candidateId, String providerType, String modelId) {
        TraceRow row = require(traceId);
        synchronized (lock) {
            String attemptId = UUID.randomUUID().toString();
            row.attempts.add(new AttemptRow(attemptId, candidateId, "RUNNING", null));
            return attemptId;
        }
    }

    @Override
    public void finishAttempt(String traceId, String attemptId, String status, String errorCode,
                              long inputTokens, long outputTokens, String usageSource,
                              String costAmount, String costCurrency, boolean costEstimated) {
        TraceRow row = require(traceId);
        synchronized (lock) {
            for (AttemptRow attempt : row.attempts) {
                if (attempt.attemptId().equals(attemptId)) {
                    if (!"RUNNING".equals(attempt.status())) {
                        throw new LightAiException(ErrorCode.INTERNAL_ERROR, "已结束的 Attempt 不允许状态回退");
                    }
                    row.attempts.set(row.attempts.indexOf(attempt), new AttemptRow(
                            attempt.attemptId(), attempt.candidateId(), status, errorCode));
                    return;
                }
            }
            throw new LightAiException(ErrorCode.INTERNAL_ERROR, "Attempt 不存在");
        }
    }

    @Override
    public void markCommitted(String traceId) {
        require(traceId).committed = true;
    }

    @Override
    public boolean committed(String traceId) {
        return require(traceId).committed;
    }

    @Override
    public void finalizeTrace(String traceId, String status) {
        TraceRow row = require(traceId);
        synchronized (lock) {
            if (row.finalized) {
                throw new LightAiException(ErrorCode.INTERNAL_ERROR, "已结束的 Trace 不允许回退到运行状态");
            }
            row.finalized = true;
            row.status = status;
        }
    }

    @Override
    public List<AttemptView> attempts(String traceId) {
        TraceRow row = require(traceId);
        synchronized (lock) {
            return row.attempts.stream()
                    .map(attempt -> new AttemptView(attempt.attemptId(), attempt.status(),
                            attempt.errorCode(), attempt.candidateId()))
                    .toList();
        }
    }

    public String statusOf(String traceId) {
        return require(traceId).status;
    }

    /** 已创建的 Trace ID（测试观测用）。 */
    public java.util.List<String> createdTraceIds() {
        synchronized (lock) {
            return List.copyOf(traces.keySet());
        }
    }

    private TraceRow require(String traceId) {
        TraceRow row = traces.get(traceId);
        if (row == null) {
            throw new LightAiException(ErrorCode.INTERNAL_ERROR, "Trace 不存在");
        }
        return row;
    }

    private static final class TraceRow {
        private final String traceId;
        private final String model;
        private final String application;
        private final List<AttemptRow> attempts = new CopyOnWriteArrayList<>();
        private volatile boolean committed;
        private volatile boolean finalized;
        private volatile String status = "RUNNING";

        private TraceRow(String traceId, String model, String application) {
            this.traceId = traceId;
            this.model = model;
            this.application = application;
        }
    }

    private record AttemptRow(String attemptId, String candidateId, String status, String errorCode) {
    }
}
