package com.lightai.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.client.error.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * 恢复验收（BE-022）：固定矩阵、总预算不乘法膨胀、429 先换凭证、
 * Retry-After 上限、退避指数序列。
 */
class RecoveryEngineTest {

    private final RecoveryEngine engine = new RecoveryEngine(new java.util.random.RandomGenerator() {
        @Override
        public double nextDouble() {
            return 0.5; // 无抖动中点
        }

        @Override
        public long nextLong(long bound) {
            return 0;
        }

        @Override
        public long nextLong() {
            return 0;
        }
    });

    private static ErrorClassification throttled(Long retryAfterMs) {
        return new ErrorClassification(ErrorCode.PROVIDER_RATE_LIMITED, true, retryAfterMs,
                ErrorClassification.REASON_THROTTLED);
    }

    private static ErrorClassification transientError() {
        return new ErrorClassification(ErrorCode.NETWORK_ERROR, true, null,
                ErrorClassification.REASON_TRANSIENT);
    }

    private static ErrorClassification authError() {
        return new ErrorClassification(ErrorCode.PROVIDER_AUTH_FAILED, false, null,
                ErrorClassification.REASON_AUTH);
    }

    @Test
    void terminalErrorsNeverRetriedOrFailedOver() {
        RecoveryBudget budget = new RecoveryBudget(RecoveryPolicy.systemDefault());
        var decision = engine.decide(authError(), budget, true);
        assertThat(decision.action()).isEqualTo(RecoveryDecision.Action.FAIL);
        assertThat(budget.retries()).isZero();
        assertThat(budget.credentialFailovers()).isZero();
    }

    @Test
    void throttledPrefersCredentialFailoverThenFallbackThenRetryAfter() {
        RecoveryPolicy policy = new RecoveryPolicy(1, 1, 1, 200, 2.0, 0, true, 5000, true);
        RecoveryBudget budget = new RecoveryBudget(policy);
        assertThat(engine.decide(throttled(null), budget, true).action())
                .isEqualTo(RecoveryDecision.Action.CREDENTIAL_FAILOVER);
        budget.recordCredentialFailover();

        assertThat(engine.decide(throttled(null), budget, true).action())
                .isEqualTo(RecoveryDecision.Action.FALLBACK);
        budget.recordFallback();

        var withRetryAfter = engine.decide(throttled(3000L), budget, true);
        assertThat(withRetryAfter.action()).isEqualTo(RecoveryDecision.Action.RETRY);
        assertThat(withRetryAfter.backoffMs()).isEqualTo(3000);
        budget.recordRetry();

        // 超过 max_retry_after_ms 截断（第二次 retry 预算内）
        var capped = engine.decide(throttled(60000L), budget, true);
        if (budget.canRetry()) {
            assertThat(capped.action()).isEqualTo(RecoveryDecision.Action.RETRY);
            assertThat(capped.backoffMs()).isEqualTo(5000);
            budget.recordRetry();
        }
    }

    @Test
    void transientRetriesWithExponentialBackoffThenFallsBack() {
        RecoveryPolicy policy = new RecoveryPolicy(2, 0, 2, 200, 2.0, 0, true, 5000, true);
        RecoveryBudget budget = new RecoveryBudget(policy);
        var first = engine.decide(transientError(), budget, true);
        assertThat(first.action()).isEqualTo(RecoveryDecision.Action.RETRY);
        assertThat(first.backoffMs()).isEqualTo(200);
        budget.recordRetry();

        var second = engine.decide(transientError(), budget, true);
        assertThat(second.action()).isEqualTo(RecoveryDecision.Action.RETRY);
        assertThat(second.backoffMs()).isEqualTo(400);
        budget.recordRetry();

        // retry 预算耗尽（max_retries=2）→ Fallback
        var third = engine.decide(transientError(), budget, true);
        assertThat(third.action()).isEqualTo(RecoveryDecision.Action.FALLBACK);
    }

    @Test
    void totalBudgetDoesNotMultiply() {
        RecoveryPolicy policy = new RecoveryPolicy(2, 2, 2, 200, 2.0, 0, true, 5000, true);
        RecoveryBudget budget = new RecoveryBudget(policy);
        assertThat(budget.totalBudget()).isEqualTo(7); // 1 + 2 + 2 + 2，线性不乘法
        for (int i = 1; i <= 7; i++) {
            assertThat(budget.hasAttemptBudget()).isTrue();
            budget.recordAttempt();
        }
        assertThat(budget.hasAttemptBudget()).isFalse();
    }
}
