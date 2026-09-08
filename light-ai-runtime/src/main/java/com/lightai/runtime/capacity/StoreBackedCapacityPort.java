package com.lightai.runtime.capacity;

import com.lightai.runtime.ports.CapacityPort;

import java.util.UUID;

/**
 * 将容量存储适配为运行时容量端口，并保持预占、结算与释放的幂等语义。
 */
public final class StoreBackedCapacityPort implements CapacityPort {

    private final CapacityStore store;

    public StoreBackedCapacityPort(CapacityStore store) {
        this.store = store;
    }

    @Override
    public Reservation reserve(String aliasId, String modelId, String credentialId, long estimatedTokens) {
        return reserve(aliasId, modelId, credentialId,
                estimatedTokens, 0, null, null, null);
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
