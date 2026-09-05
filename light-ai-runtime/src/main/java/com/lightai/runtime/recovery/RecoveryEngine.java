package com.lightai.runtime.recovery;

import java.util.random.RandomGenerator;

/**
 * 恢复引擎（BE-022）。
 * 固定矩阵：认证/参数类 → FAIL（不重试不换密钥）；429 → 优先
 * CREDENTIAL_FAILOVER，预算耗尽再 Fallback，最后才允许预算内 Retry-After
 * 重试（尊重上游头，上限 max_retry_after_ms）；瞬时错误 → RETRY 指数退避
 * （initial × multiplier^序号，抖动 ±jitter_percent）；预算耗尽 → FAIL。
 * 决策只读预算，不修改（由调用方按动作记账，先结算再下一次 Attempt）。
 */
public class RecoveryEngine {

    private final RandomGenerator random;

    public RecoveryEngine(RandomGenerator random) {
        this.random = random;
    }

    public RecoveryDecision decide(ErrorClassification classification, RecoveryBudget budget,
                                   boolean fallbackCandidateAvailable) {
        // 1. 终态错误：认证/参数类重试无意义
        if (classification.isTerminal()) {
            return fail(classification, "TERMINAL_ERROR");
        }
        // 2. 429：先换凭证，再 Fallback，最后才允许预算内 Retry-After 重试
        if (classification.isThrottled()) {
            if (budget.canFailoverCredential()) {
                return new RecoveryDecision(RecoveryDecision.Action.CREDENTIAL_FAILOVER,
                        "THROTTLED_FAILOVER", 0, classification.errorCode().name(),
                        budget.totalAttempts());
            }
            if (fallbackCandidateAvailable && budget.canFallback()) {
                return new RecoveryDecision(RecoveryDecision.Action.FALLBACK,
                        "THROTTLED_FALLBACK", 0, classification.errorCode().name(),
                        budget.totalAttempts());
            }
            if (budget.canRetry()) {
                return new RecoveryDecision(RecoveryDecision.Action.RETRY, "THROTTLED_RETRY_AFTER",
                        retryAfterBackoff(classification, budget.policy()), classification.errorCode().name(),
                        budget.totalAttempts());
            }
            return fail(classification, "THROTTLED_EXHAUSTED");
        }
        // 3. 瞬时错误：退避重试 → Fallback → FAIL
        if (classification.retryable()) {
            if (budget.canRetry()) {
                return new RecoveryDecision(RecoveryDecision.Action.RETRY, "TRANSIENT_RETRY",
                        exponentialBackoff(budget.policy(), budget.retries()),
                        classification.errorCode().name(), budget.totalAttempts());
            }
            if (fallbackCandidateAvailable && budget.canFallback()) {
                return new RecoveryDecision(RecoveryDecision.Action.FALLBACK, "TRANSIENT_FALLBACK",
                        0, classification.errorCode().name(), budget.totalAttempts());
            }
            return fail(classification, "BUDGET_EXHAUSTED");
        }
        // 4. 其余不可重试错误：尝试 Fallback，否则 FAIL
        if (fallbackCandidateAvailable && budget.canFallback()) {
            return new RecoveryDecision(RecoveryDecision.Action.FALLBACK, "NON_RETRYABLE_FALLBACK",
                    0, classification.errorCode().name(), budget.totalAttempts());
        }
        return fail(classification, "NON_RETRYABLE");
    }

    private static RecoveryDecision fail(ErrorClassification classification, String reasonCode) {
        return new RecoveryDecision(RecoveryDecision.Action.FAIL, reasonCode, 0,
                classification.errorCode().name(), classification.attemptSequenceHint());
    }

    /** 指数退避 + 抖动：initial × multiplier^序号，抖动 ±jitter_percent。 */
    long exponentialBackoff(RecoveryPolicy policy, int retryOrdinal) {
        double base = policy.initialBackoffMs()
                * Math.pow(policy.backoffMultiplier(), Math.max(retryOrdinal, 0));
        long backoff = Math.min((long) base, 60_000L);
        int jitter = policy.jitterPercent();
        if (jitter <= 0) {
            return backoff;
        }
        double spread = backoff * jitter / 100.0;
        long jitterDelta = (long) (spread * (random.nextDouble() * 2 - 1));
        return Math.max(0, backoff + jitterDelta);
    }

    private static long retryAfterBackoff(ErrorClassification classification, RecoveryPolicy policy) {
        if (!policy.respectRetryAfter() || classification.retryAfterMs() == null) {
            return policy.initialBackoffMs();
        }
        return Math.min(classification.retryAfterMs(), policy.maxRetryAfterMs());
    }
}
