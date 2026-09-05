package com.lightai.runtime.recovery;

import com.lightai.client.error.ErrorCode;
import java.util.List;

/**
 * 错误分类（BE-022 输入）：Adapter 按 4.7.3 分类后进入恢复引擎。
 * isFailureForCircuit：429 不计入熔断失败（BE-023 验收）。
 */
public record ErrorClassification(ErrorCode errorCode, boolean retryable,
                                  Long retryAfterMs, String reasonCode) {

    public static final String REASON_THROTTLED = "THROTTLED";
    public static final String REASON_TRANSIENT = "TRANSIENT";
    public static final String REASON_AUTH = "AUTH";
    public static final String REASON_PARAM = "PARAM";
    public static final String REASON_CONTENT_FILTER = "CONTENT_FILTER";

    /** 429/限流：不计熔断失败，优先换凭证。 */
    public boolean isThrottled() {
        return errorCode == ErrorCode.PROVIDER_RATE_LIMITED
                || errorCode == ErrorCode.CAPACITY_LIMITED
                || REASON_THROTTLED.equals(reasonCode);
    }

    /** 认证/参数类错误：不重试、不换密钥（重试注定失败）。 */
    public boolean isTerminal() {
        return errorCode == ErrorCode.PROVIDER_AUTH_FAILED
                || errorCode == ErrorCode.PROVIDER_REQUEST_REJECTED
                || REASON_AUTH.equals(reasonCode) || REASON_PARAM.equals(reasonCode);
    }

    /** 熔断窗口是否计为失败：429 与客户端取消不计（BE-023 验收）。 */
    public boolean countsAsCircuitFailure() {
        return !isThrottled() && errorCode != ErrorCode.CLIENT_CANCELLED;
    }

    /** fail() 构造用：失败时通常尚无下一次尝试序号。 */
    public int attemptSequenceHint() {
        return -1;
    }
}
