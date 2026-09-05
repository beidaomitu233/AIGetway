package com.lightai.runtime.capacity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内原子容量存储（BE-024）：Embedded 单实例允许的共享状态实现，
 * 集群模式必须替换为共享 Redis 实现（BE-P05 storage-redis）。
 * 窗口：Unix 对齐 60 秒固定窗口；三层（Alias/Model/Credential）计数同一次
 * 预占内完成，任何一层失败全部回退（无部分计数）；预留状态互斥保证
 * 结算/释放只应用一次；结算与释放均落在原预占窗口。
 */
public class InMemoryCapacityStore implements CapacityStore {

    /** 每层限制（null 表示不限），来自快照策略与 Credential 限额的最小值合成。 */
    public record ScopeLimit(Long rpmLimit, Long tpmLimit, Integer concurrentLimit) {
    }

    private static final long WINDOW_SECONDS = 60;

    private final Map<String, ScopeLimit> limits = new ConcurrentHashMap<>();
    // key: scopeType:scopeId:windowStart:metric → counter
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<UUID, Reservation> reservations = new ConcurrentHashMap<>();

    public void registerLimit(String scopeType, UUID scopeId, ScopeLimit limit) {
        limits.put(scopeType + ":" + scopeId, limit);
    }

    public void clearLimits() {
        limits.clear();
        counters.clear();
        reservations.clear();
    }

    private static long windowStart(Instant now) {
        return now.getEpochSecond() / WINDOW_SECONDS * WINDOW_SECONDS;
    }

    private AtomicLong counter(String scopeType, UUID scopeId, long window, String metric) {
        return counters.computeIfAbsent(
                scopeType + ":" + scopeId + ":" + window + ":" + metric,
                key -> new AtomicLong());
    }

    @Override
    public ReservationHandle reserve(ReserveRequest request) {
        long window = windowStart(Instant.now());
        UUID reservationId = UUID.randomUUID();
        String[] scopeTypes = {"alias", "provider_model", "credential"};
        UUID[] scopeIds = {request.aliasId(), request.providerModelId(), request.credentialId()};
        long tokens = Math.max(request.estimatedTokens(), 0);

        List<CounterDelta> applied = new ArrayList<>();
        List<CounterDelta> rpmDeltas = new ArrayList<>();
        List<CounterDelta> tpmDeltas = new ArrayList<>();
        List<CounterDelta> concurrentDeltas = new ArrayList<>();
        try {
            for (int i = 0; i < scopeTypes.length; i++) {
                ScopeLimit limit = limits.get(scopeTypes[i] + ":" + scopeIds[i]);
                if (limit == null) {
                    continue;
                }
                if (limit.rpmLimit() != null) {
                    AtomicLong counter = counter(scopeTypes[i], scopeIds[i], window, "rpm");
                    long updated = counter.incrementAndGet();
                    applied.add(new CounterDelta(counter, -1));
                    rpmDeltas.add(new CounterDelta(counter, -1));
                    if (updated > limit.rpmLimit()) {
                        throw new CapacityLimitedException("RPM 不足：" + scopeTypes[i]);
                    }
                }
                if (limit.tpmLimit() != null) {
                    AtomicLong counter = counter(scopeTypes[i], scopeIds[i], window, "tpm");
                    long updated = counter.addAndGet(tokens);
                    applied.add(new CounterDelta(counter, -tokens));
                    tpmDeltas.add(new CounterDelta(counter, -tokens));
                    if (updated > limit.tpmLimit()) {
                        throw new CapacityLimitedException("TPM 不足：" + scopeTypes[i]);
                    }
                }
                if (limit.concurrentLimit() != null) {
                    AtomicLong counter = counter(scopeTypes[i], scopeIds[i], window, "concurrent");
                    long updated = counter.incrementAndGet();
                    applied.add(new CounterDelta(counter, -1));
                    concurrentDeltas.add(new CounterDelta(counter, -1));
                    if (updated > limit.concurrentLimit()) {
                        throw new CapacityLimitedException("并发不足：" + scopeTypes[i]);
                    }
                }
            }
        } catch (CapacityLimitedException | CapacityStateUnavailableException e) {
            applied.forEach(CounterDelta::undo);
            throw e;
        }

        reservations.put(reservationId, new Reservation(window, tokens,
                List.copyOf(rpmDeltas), List.copyOf(tpmDeltas), List.copyOf(concurrentDeltas)));
        return new ReservationHandle(reservationId, window, tokens);
    }

    @Override
    public void settle(UUID reservationId, long actualTokens, boolean requestSent) {
        Reservation reservation = reservations.get(reservationId);
        if (reservation == null || !reservation.markTerminal()) {
            return; // 幂等：未知预留或已终态
        }
        // 并发占用随终态释放；结算在原预占窗口按实际用量修正 TPM
        undoConcurrent(reservation);
        long delta = Math.max(actualTokens, 0) - reservation.reservedTokens;
        if (delta != 0) {
            for (CounterDelta tpmDelta : reservation.tpmDeltas) {
                tpmDelta.counter.addAndGet(delta);
            }
        }
        if (!requestSent) {
            // 未发送请求：退还未发送 RPM
            for (CounterDelta rpmDelta : reservation.rpmDeltas) {
                rpmDelta.counter.decrementAndGet();
            }
        }
    }

    @Override
    public void release(UUID reservationId) {
        Reservation reservation = reservations.get(reservationId);
        if (reservation == null || !reservation.markTerminal()) {
            return;
        }
        undoConcurrent(reservation);
        // 取消/超时视为未发送：全额退回预留 RPM/TPM
        for (CounterDelta rpmDelta : reservation.rpmDeltas) {
            rpmDelta.counter.decrementAndGet();
        }
        for (CounterDelta tpmDelta : reservation.tpmDeltas) {
            tpmDelta.counter.addAndGet(tpmDelta.amount());
        }
    }

    @Override
    public UsageSnapshot usage(String scopeType, UUID scopeId) {
        long window = windowStart(Instant.now());
        return new UsageSnapshot(window,
                counter(scopeType, scopeId, window, "rpm").get(),
                counter(scopeType, scopeId, window, "tpm").get(),
                0,
                counter(scopeType, scopeId, window, "concurrent").get());
    }

    private void undoConcurrent(Reservation reservation) {
        for (CounterDelta concurrentDelta : reservation.concurrentDeltas) {
            concurrentDelta.counter.decrementAndGet();
        }
    }

    /** 已应用的计数增量；undo 记录负值增量。 */
    private record CounterDelta(AtomicLong counter, long amount) {
        void undo() {
            counter.addAndGet(amount);
        }
    }

    /** 预留状态：分组计数引用 + 终态互斥（结算/释放只应用一次）。 */
    private static final class Reservation {
        private final long windowStart;
        private final long reservedTokens;
        private final List<CounterDelta> rpmDeltas;
        private final List<CounterDelta> tpmDeltas;
        private final List<CounterDelta> concurrentDeltas;
        private boolean terminal;

        Reservation(long windowStart, long reservedTokens, List<CounterDelta> rpmDeltas,
                    List<CounterDelta> tpmDeltas, List<CounterDelta> concurrentDeltas) {
            this.windowStart = windowStart;
            this.reservedTokens = reservedTokens;
            this.rpmDeltas = rpmDeltas;
            this.tpmDeltas = tpmDeltas;
            this.concurrentDeltas = concurrentDeltas;
        }

        synchronized boolean markTerminal() {
            if (terminal) {
                return false;
            }
            terminal = true;
            return true;
        }
    }
}
