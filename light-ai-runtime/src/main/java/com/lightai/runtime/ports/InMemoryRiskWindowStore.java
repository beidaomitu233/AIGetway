package com.lightai.runtime.ports;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Embedded 单实例风险窗口；Standalone 必须装配共享 Redis 实现。 */
public final class InMemoryRiskWindowStore implements RiskWindowStore {
    private final Map<String, MutableWindow> windows = new ConcurrentHashMap<>();

    @Override
    public Window increment(String key, long ttlSeconds, long requestDelta, long tokenDelta, BigDecimal amountDelta) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("风险窗口 key 不能为空");
        MutableWindow window = windows.computeIfAbsent(key, ignored -> new MutableWindow());
        synchronized (window) {
            window.requests += Math.max(0, requestDelta);
            window.tokens += Math.max(0, tokenDelta);
            window.amount = window.amount.add(amountDelta == null ? BigDecimal.ZERO : amountDelta.max(BigDecimal.ZERO));
            return new Window(window.requests, window.tokens, window.amount);
        }
    }

    private static final class MutableWindow {
        private long requests;
        private long tokens;
        private BigDecimal amount = BigDecimal.ZERO;
    }
}
