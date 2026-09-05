package com.lightai.runtime.recovery;

/**
 * 恢复策略参数（来自固定快照的 ReliabilityPolicy 值）。
 */
public record RecoveryPolicy(int maxRetries, int maxCredentialFailovers, int maxFallbacks,
                             long initialBackoffMs, double backoffMultiplier,
                             int jitterPercent, boolean respectRetryAfter,
                             long maxRetryAfterMs, boolean fallbackEnabled) {

    /** 内置默认策略（策略停用时采用）。 */
    public static RecoveryPolicy systemDefault() {
        return new RecoveryPolicy(1, 1, 2, 200, 2.0, 20, true, 5000, true);
    }
}
