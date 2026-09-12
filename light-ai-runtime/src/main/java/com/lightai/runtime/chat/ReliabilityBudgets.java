package com.lightai.runtime.chat;


/**
 * 可靠性预算（PRD 9.5）：每类恢复动作独立次数上限，
 * 总尝试 = 1 + max_retries + max_credential_failovers + max_priority_fallbacks + max_fallbacks。
 * 同优先级换候选与下一优先级候选分别计数，不能跨级稀释主备关系。
 */
public record ReliabilityBudgets(int maxRetries, int maxCredentialFailovers,
                                 int maxPriorityFallbacks, int maxFallbacks) {

    public static final ReliabilityBudgets DEFAULT = new ReliabilityBudgets(1, 1, 1, 1);

    public ReliabilityBudgets(int maxRetries, int maxCredentialFailovers, int maxFallbacks) {
        this(maxRetries, maxCredentialFailovers, 1, maxFallbacks);
    }

    public int totalExternalAttempts() {
        return 1 + maxRetries + maxCredentialFailovers + maxPriorityFallbacks + maxFallbacks;
    }

    /** 端口形态：默认预算由桩返回。 */
    public interface Port {
        ReliabilityBudgets budgets();
    }

    static Port defaultPort() {
        return () -> DEFAULT;
    }

}
