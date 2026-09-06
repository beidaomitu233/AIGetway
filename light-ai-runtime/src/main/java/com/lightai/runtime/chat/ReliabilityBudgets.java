package com.lightai.runtime.chat;


/**
 * 可靠性预算端口（BE-P04 ReliabilityPolicy 交付前为默认桩）：
 * 固定 Trace 总预算 = 1 + max_retries + max_credential_failovers + max_fallbacks。
 */
public record ReliabilityBudgets(int maxRetries, int maxCredentialFailovers, int maxFallbacks) {

    public static final ReliabilityBudgets DEFAULT = new ReliabilityBudgets(1, 1, 1);

    public int totalExternalAttempts() {
        return 1 + maxRetries + maxCredentialFailovers + maxFallbacks;
    }

    /** 端口形态：默认预算由桩返回。 */
    public interface Port {
        ReliabilityBudgets budgets();
    }

    static Port defaultPort() {
        return () -> DEFAULT;
    }

}
