package com.lightai.runtime.trace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Trace 生命周期端口（BE-P06 交付前为内存契约实现）：唯一 trace_id 占位、
 * Attempt 时间线、response_committed 标记与最终化；DB-P03 迁移落地后由 JDBC 实现替换。
 */
public interface TraceStore {

    /** 创建 Trace；客户端提供 trace_id 冲突时抛 TRACE_ID_CONFLICT（不提供业务幂等重放）。 */
    TraceHandle create(String clientTraceIdOrNull, String model, String application);

    /** 每次实际向 Provider 发出请求前创建 RUNNING Attempt。 */
    String startAttempt(String traceId, String candidateId, String providerType, String modelId);

    /**
     * 每次实际向 Provider 发出请求前创建 RUNNING Attempt，并固定该 Attempt 的
     * 真实运行身份（BE-223：单渠道、真实上游模型、单渠道 Key）与调用时价格快照。
     */
    default String startAttempt(String traceId, AttemptIdentity identity) {
        return startAttempt(traceId,
                identity.routeCandidateId() == null ? null : identity.routeCandidateId().toString(),
                identity.providerType(), identity.upstreamModelName());
    }

    /** Attempt 终态：SUCCEEDED/FAILED/CANCELLED；已结束的 Attempt 不允许回退。 */
    void finishAttempt(String traceId, String attemptId, String status, String errorCode,
                       long inputTokens, long outputTokens, String usageSource,
                       String costAmount, String costCurrency, boolean costEstimated);

    /** Attempt 终态并写入费用分量（input/output/total），供账本与观测对账。 */
    default void finishAttempt(String traceId, String attemptId, String status, String errorCode,
                               long inputTokens, long outputTokens, String usageSource,
                               BigDecimal inputCost, BigDecimal outputCost, BigDecimal totalCost,
                               String currency, boolean costEstimated) {
        finishAttempt(traceId, attemptId, status, errorCode, inputTokens, outputTokens, usageSource,
                totalCost == null ? null : totalCost.toPlainString(), currency, costEstimated);
    }

    /** 标记首个业务块已提交（BE-028 提交后禁止换路径）。 */
    void markCommitted(String traceId);

    boolean committed(String traceId);

    /** Trace 最终化：SUCCEEDED/FAILED/CANCELLED/STREAM_INTERRUPTED；写 ended_at。 */
    void finalizeTrace(String traceId, String status);

    /**
     * 收敛超过自身 deadline 仍处于 RUNNING/QUEUED 的 Trace（BE-225 进程崩溃恢复）：
     * Attempt 补终态、Trace 进入 FAILED，返回本次收敛条数；实现必须幂等可重入。
     */
    default int finalizeExpired(Instant now, String errorCode) {
        return 0;
    }

    record TraceHandle(String traceId, long snapshotNo) {
    }

    record AttemptView(String attemptId, String status, String errorCode, String candidateId) {
    }

    /**
     * Attempt 运行身份与价格快照：UUID 缺失时实现回退旧占位逻辑；
     * 价格为调用开始时的快照，结算不回写改价。
     */
    record AttemptIdentity(UUID routeCandidateId, UUID channelId, UUID upstreamModelId,
                           UUID channelCredentialId, String providerType, String upstreamModelName,
                           String credentialMask, BigDecimal inputPrice, BigDecimal outputPrice,
                           int priceUnit, String currency) {
    }

    /** 全部 Attempt（时间线按开始顺序）。 */
    List<AttemptView> attempts(String traceId);
}
