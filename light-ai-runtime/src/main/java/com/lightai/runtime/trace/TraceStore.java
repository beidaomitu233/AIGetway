package com.lightai.runtime.trace;

import java.util.List;

/**
 * Trace 生命周期端口（BE-P06 交付前为内存契约实现）：唯一 trace_id 占位、
 * Attempt 时间线、response_committed 标记与最终化；DB-P03 迁移落地后由 JDBC 实现替换。
 */
public interface TraceStore {

    /** 创建 Trace；客户端提供 trace_id 冲突时抛 TRACE_ID_CONFLICT（不提供业务幂等重放）。 */
    TraceHandle create(String clientTraceIdOrNull, String model, String application);

    /** 每次实际向 Provider 发出请求前创建 RUNNING Attempt。 */
    String startAttempt(String traceId, String candidateId, String providerType, String modelId);

    /** Attempt 终态：SUCCEEDED/FAILED/CANCELLED；已结束的 Attempt 不允许回退。 */
    void finishAttempt(String traceId, String attemptId, String status, String errorCode,
                       long inputTokens, long outputTokens, String usageSource,
                       String costAmount, String costCurrency, boolean costEstimated);

    /** 标记首个业务块已提交（BE-028 提交后禁止换路径）。 */
    void markCommitted(String traceId);

    boolean committed(String traceId);

    /** Trace 最终化：SUCCEEDED/FAILED/CANCELLED/STREAM_INTERRUPTED；写 ended_at。 */
    void finalizeTrace(String traceId, String status);

    record TraceHandle(String traceId, long snapshotNo) {
    }

    record AttemptView(String attemptId, String status, String errorCode, String candidateId) {
    }

    /** 全部 Attempt（时间线按开始顺序）。 */
    List<AttemptView> attempts(String traceId);
}
