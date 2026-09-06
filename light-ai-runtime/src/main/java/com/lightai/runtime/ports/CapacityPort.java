package com.lightai.runtime.ports;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;

/**
 * 容量端口（BE-P04 CapacityStore 交付后为共享原子实现）：三层预占的运行侧抽象。
 * 共享状态不可用时不创建新预占（CAPACITY_STATE_UNAVAILABLE）。
 */
public interface CapacityPort {

    /** Alias/Model/Credential 同维度预占；部分失败全不计数。 */
    Reservation reserve(String aliasId, String modelId, String credentialId, long estimatedTokens);

    /** 结算在原预占窗口；调用结束（成功/失败按实际或估算用量）。 */
    void settle(String reservationId, long inputTokens, long outputTokens);

    /** 释放（未发出请求、取消、排队失败）；并发各终止路径只释放一次。 */
    void release(String reservationId);

    record Reservation(String reservationId, String aliasId, String modelId, String credentialId) {
    }

    static CapacityPort unlimited() {
        return new CapacityPort() {
            @Override
            public Reservation reserve(String aliasId, String modelId, String credentialId, long estimatedTokens) {
                return new Reservation(java.util.UUID.randomUUID().toString(), aliasId, modelId, credentialId);
            }

            @Override
            public void settle(String reservationId, long inputTokens, long outputTokens) {
            }

            @Override
            public void release(String reservationId) {
            }
        };
    }

    static LightAiException capacityLimited() {
        return new LightAiException(ErrorCode.CAPACITY_LIMITED, "RPM、TPM 或并发容量不足");
    }
}
