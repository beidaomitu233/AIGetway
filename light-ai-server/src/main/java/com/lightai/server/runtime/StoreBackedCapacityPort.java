package com.lightai.server.runtime;

import com.lightai.runtime.capacity.CapacityStore;
import com.lightai.runtime.ports.CapacityPort;
import java.util.UUID;

/**
 * CapacityStore → CapacityPort 运行适配（BE-P04 接线）：
 * 预占失败（限额/共享状态不可用）原样透传给管道恢复判定；
 * 结算把实际用量写入原预占窗口，释放幂等。
 */
public final class StoreBackedCapacityPort implements CapacityPort {

    private final CapacityStore store;

    public StoreBackedCapacityPort(CapacityStore store) {
        this.store = store;
    }

    @Override
    public Reservation reserve(String aliasId, String modelId, String credentialId, long estimatedTokens) {
        return reserve(aliasId, modelId, credentialId, estimatedTokens, 0, null, null, null);
    }

    @Override
    public Reservation reserve(String aliasId, String modelId, String credentialId,
                               long estimatedTokens, long maxTokens,
                               CapacityStore.ScopeLimit aliasLimit,
                               CapacityStore.ScopeLimit modelLimit,
                               CapacityStore.ScopeLimit credentialLimit) {
        CapacityStore.ReservationHandle handle = store.reserve(new CapacityStore.ReserveRequest(
                uuid(aliasId), uuid(modelId), uuid(credentialId), estimatedTokens, maxTokens,
                aliasLimit, modelLimit, credentialLimit));
        return new Reservation(handle.reservationId().toString(), aliasId, modelId, credentialId);
    }

    @Override
    public void settle(String reservationId, long inputTokens, long outputTokens) {
        store.settle(UUID.fromString(reservationId), inputTokens + outputTokens, true);
    }

    @Override
    public void release(String reservationId) {
        store.release(UUID.fromString(reservationId));
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
