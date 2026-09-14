package com.lightai.runtime.ports;

import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.error.LightAiException;
import java.math.BigDecimal;

/** 上游调用前的风险准入端口；共享策略不可用时实现应拒绝新请求。 */
public interface RiskControlPort {
    void check(AccessTokenPort.Principal principal, String requestId, UnifiedChatRequest request)
            throws LightAiException;

    /** 带本次请求保守用量估算的准入检查；旧实现默认沿用原契约。 */
    default void check(AccessTokenPort.Principal principal, String requestId,
                       UnifiedChatRequest request, long estimatedTokens, BigDecimal estimatedAmount)
            throws LightAiException {
        check(principal, requestId, request);
    }

    static RiskControlPort allowAll() {
        return (principal, requestId, request) -> { };
    }
}
