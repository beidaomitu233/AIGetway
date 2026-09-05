package com.lightai.runtime.recovery;

/**
 * 恢复决策（不可变记录，失败后生成，无二次迁移）。
 * action：RETRY/CREDENTIAL_FAILOVER/FALLBACK/FAIL。
 */
public record RecoveryDecision(Action action, String reasonCode, long backoffMs,
                               String errorCode, int attemptSequence) {

    public enum Action {
        RETRY,
        CREDENTIAL_FAILOVER,
        FALLBACK,
        FAIL
    }
}
