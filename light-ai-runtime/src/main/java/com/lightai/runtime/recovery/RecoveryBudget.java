package com.lightai.runtime.recovery;

/**
 * Trace 级恢复预算（BE-022 验收核心）。
 * 总尝试上限 = 1 + max_retries + max_credential_failovers + max_fallbacks；
 * 各动作预算独立累计于整个 Trace，先结算再下一次 Attempt，不发生乘法膨胀。
 */
public final class RecoveryBudget {

    private final RecoveryPolicy policy;
    private int totalAttempts;
    private int retries;
    private int credentialFailovers;
    private int fallbacks;

    public RecoveryBudget(RecoveryPolicy policy) {
        this.policy = policy;
    }

    public int totalAttempts() {
        return totalAttempts;
    }

    public RecoveryPolicy policy() {
        return policy;
    }

    public int retries() {
        return retries;
    }

    public int credentialFailovers() {
        return credentialFailovers;
    }

    public int fallbacks() {
        return fallbacks;
    }

    /** 记录一次 Attempt（外部调用）开始。 */
    public void recordAttempt() {
        totalAttempts++;
    }

    public boolean canRetry() {
        return retries < policy.maxRetries();
    }

    public boolean canFailoverCredential() {
        return credentialFailovers < policy.maxCredentialFailovers();
    }

    public boolean canFallback() {
        return policy.fallbackEnabled() && fallbacks < policy.maxFallbacks();
    }

    public void recordRetry() {
        retries++;
    }

    public void recordCredentialFailover() {
        credentialFailovers++;
    }

    public void recordFallback() {
        fallbacks++;
    }

    /** 总尝试预算（含首次）。 */
    public int totalBudget() {
        return 1 + policy.maxRetries() + policy.maxCredentialFailovers() + policy.maxFallbacks();
    }

    public boolean hasAttemptBudget() {
        return totalAttempts < totalBudget();
    }
}
