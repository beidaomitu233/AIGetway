package com.lightai.admin.check;

import java.util.UUID;

/**
 * 检测执行端口（BE-009/013/014/015/017 共用）。
 * BE-P03 阶段仅约定契约与记录持久化；真实 Provider 调用由 BE-P05 四内置
 * Adapter 实现注入，本端口实现方必须保证：每次方法只发起一次外部请求、
 * 不内置重试、不缓存 Secret、异常映射为统一错误码。
 */
public interface CheckInvoker {

    /** 是否支持该 Provider 类型的检测调用。 */
    boolean supports(String providerType);

    Outcome invoke(Invocation invocation);

    /** 检测目标：模型+凭证+超时；Secret 由调用方解析后经 SecretHandle 传入，不得落日志。 */
    record Invocation(
            String providerType,
            String baseUrl,
            String modelId,
            UUID credentialId,
            byte[] resolvedSecret,
            String mode,
            int timeoutMs) {
    }

    record Outcome(
            boolean succeeded,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            String usageSource,
            String providerRequestId,
            String errorCode,
            String errorSummary,
            String traceId,
            UUID attemptId) {

        public static Outcome unsupported(String providerType) {
            return new Outcome(false, null, null, null, null, null,
                    "PROVIDER_ADAPTER_NOT_FOUND", "Provider 类型未加载 Adapter: " + providerType, null, null);
        }
    }
}
