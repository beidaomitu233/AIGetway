package com.lightai.runtime.capacity;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.UUID;

/**
 * 容量共享状态存储端口（BE-024）。
 * 真相源：容量实时状态在本存储；SQL 为可追踪结算事实（P05 装配）。
 * 语义：三层（Alias/Model/Credential）同次原子预占，部分失败全不计数；
 * 结算在原预占窗口；取消/超时争抢一次终态，重复释放幂等。
 * 共享状态不可用时实现必须抛 CAPACITY_STATE_UNAVAILABLE，禁止退化为独立计数。
 */
public interface CapacityStore {

    /** 三层原子预占；requestCount 固定为 1 次 RPM 计数。 */
    ReservationHandle reserve(ReserveRequest request);

    /** 结算：实际用量写入原预占窗口；requestSent=false 时退还未发送 RPM。 */
    void settle(UUID reservationId, long actualTokens, boolean requestSent);

    /** 释放：取消/超时/失败路径；幂等，仅首次生效。 */
    void release(UUID reservationId);

    /** 作用对象当前窗口用量快照（管理查询只读）。 */
    UsageSnapshot usage(String scopeType, UUID scopeId);

    record ReserveRequest(UUID aliasId, UUID providerModelId, UUID credentialId,
                          long estimatedTokens, long maxTokens) {
    }

    /** 预占句柄：reservation_id 为重复释放/结算幂等键。 */
    record ReservationHandle(UUID reservationId, long windowStartEpochSecond,
                             long reservedTokens) {
    }

    /** 用量快照：当前窗口 RPM/TPM 预占与结算、并发。 */
    record UsageSnapshot(long windowStartEpochSecond, long rpmReserved, long tpmReserved,
                         long tpmSettled, long concurrentActive) {
    }

    class CapacityStateUnavailableException extends LightAiException {
        public CapacityStateUnavailableException(String message) {
            super(ErrorCode.CAPACITY_STATE_UNAVAILABLE, message);
        }
    }

    class CapacityLimitedException extends LightAiException {
        public CapacityLimitedException(String message) {
            super(ErrorCode.CAPACITY_LIMITED, message);
        }
    }
}
