package com.lightai.runtime.ports;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.math.BigDecimal;

/**
 * 共享风险窗口计数端口。实现必须把请求、Token、金额增量放在一次原子操作中，
 * 连接或共享状态不可用时抛出异常，由准入层 fail-closed。
 */
public interface RiskWindowStore extends AutoCloseable {
    Window increment(String key, long ttlSeconds, long requestDelta, long tokenDelta, BigDecimal amountDelta);

    @Override
    default void close() {
    }

    record Window(long requests, long tokens, BigDecimal amount) {
        public Window {
            requests = Math.max(0, requests);
            tokens = Math.max(0, tokens);
            amount = amount == null ? BigDecimal.ZERO : amount.max(BigDecimal.ZERO);
        }
    }

    final class StateUnavailableException extends LightAiException {
        public StateUnavailableException(String message) {
            super(ErrorCode.CAPACITY_STATE_UNAVAILABLE, message);
        }
    }
}
