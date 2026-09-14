package com.lightai.runtime.ports;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Embedded 单实例风险窗口；Standalone 必须装配共享 Redis 实现。 */
public final class InMemoryRiskWindowStore implements RiskWindowStore {
    private final Map<String, MutableWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, Long> blockedUntil = new ConcurrentHashMap<>();

    @Override
    public Window increment(String key, long ttlSeconds, long requestDelta, long tokenDelta, BigDecimal amountDelta) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("风险窗口 key 不能为空");
        long now = System.currentTimeMillis();
        MutableWindow window = windows.computeIfAbsent(key, ignored -> new MutableWindow());
        synchronized (window) {
            if (window.expiresAtMillis <= now) {
                window.requests = 0;
                window.tokens = 0;
                window.amount = BigDecimal.ZERO;
                window.expiresAtMillis = now + Math.max(1, ttlSeconds) * 1000L;
            }
            window.requests += Math.max(0, requestDelta);
            window.tokens += Math.max(0, tokenDelta);
            window.amount = window.amount.add(amountDelta == null ? BigDecimal.ZERO : amountDelta.max(BigDecimal.ZERO));
            return new Window(window.requests, window.tokens, window.amount);
        }
    }

    @Override
    public boolean isBlocked(String key, long nowEpochMillis) {
        Long until = blockedUntil.get(key);
        if (until == null) return false;
        if (until <= nowEpochMillis) {
            blockedUntil.remove(key, until);
            return false;
        }
        return true;
    }

    @Override
    public void block(String key, long ttlSeconds) {
        block(key, ttlSeconds, System.currentTimeMillis());
    }

    @Override
    public void block(String key, long ttlSeconds, long nowEpochMillis) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("风险窗口 key 不能为空");
        blockedUntil.put(key, nowEpochMillis + Math.max(1, ttlSeconds) * 1000L);
    }

    private static final class MutableWindow {
        private long requests;
        private long tokens;
        private BigDecimal amount = BigDecimal.ZERO;
        private long expiresAtMillis;
    }
}
