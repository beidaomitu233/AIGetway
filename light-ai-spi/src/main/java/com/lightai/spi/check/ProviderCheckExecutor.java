package com.lightai.spi.check;

import com.lightai.client.provider.UsageSummary;
import java.util.Map;
import java.util.UUID;

/**
 * Provider 检测执行 SPI（BE-009）：由 Provider Adapter 实现（BE-P05）。
 * 一次调用只执行一次外部请求；不传递真实 Secret，凭证句柄由执行方
 * 通过 SecretProvider 即时解析；超时/取消/错误分类遵守统一协议。
 */
public interface ProviderCheckExecutor {

    /** 是否支持指定 Provider 类型。 */
    boolean supports(String providerType);

    CheckOutcome execute(CheckInvocation invocation);

    record CheckInvocation(
            String providerType,
            String baseUrl,
            String proxyUrl,
            int connectTimeoutMs,
            int readTimeoutMs,
            Map<String, String> defaultHeaders,
            String modelId,
            UUID credentialId,
            String mode,
            int timeoutMs) {
    }

    record CheckOutcome(
            boolean succeeded,
            int totalMs,
            UsageSummary usage,
            String providerRequestId,
            String traceId,
            String attemptId,
            String errorCode,
            String errorSummary) {

        public static CheckOutcome success(int totalMs, UsageSummary usage, String providerRequestId) {
            return new CheckOutcome(true, totalMs, usage, providerRequestId, null, null, null, null);
        }

        public static CheckOutcome failure(int totalMs, String errorCode, String safeSummary) {
            return new CheckOutcome(false, totalMs, null, null, null, null, errorCode, safeSummary);
        }
    }
}
