package com.lightai.spi.export;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 导出用脱敏 Trace 数据（BE-054，4.6.3.6）：
 * 仅包含调用元数据、耗时、Token 与费用统计；
 * 严禁包含请求正文、响应文本、认证凭证与密钥。
 */
public record ExportedTrace(
        String traceId,
        String modelAlias,
        String provider,
        String providerModel,
        String status,
        long totalDurationMs,
        Instant startedAt,
        Instant endedAt,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        BigDecimal costAmount,
        String costCurrency,
        String errorCode,
        String errorMessage) {
}
