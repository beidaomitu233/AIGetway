package com.lightai.spi.provider;

/**
 * 统一错误分类结果（4.7.2.5 基线）：Runtime 依据 retryable 与两类恢复允许位、
 * 熔断计数位和 ReliabilityPolicy 生成 RecoveryDecision；Adapter 自身不执行恢复。
 */
public record ProviderErrorClassification(
        String unifiedCode,
        boolean retryable,
        boolean credentialFailoverAllowed,
        boolean fallbackAllowed,
        boolean countsTowardCircuit) {
}
