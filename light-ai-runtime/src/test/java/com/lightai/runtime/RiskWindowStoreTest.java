package com.lightai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.runtime.ports.InMemoryRiskWindowStore;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RiskWindowStoreTest {
    @Test
    void atomicallyAccumulatesCountersAndHonorsSharedBlockTtl() {
        var store = new InMemoryRiskWindowStore();
        assertThat(store.increment("app:window", 60, 1, 4, new BigDecimal("0.50")))
                .extracting("requests", "tokens", "amount")
                .containsExactly(1L, 4L, new BigDecimal("0.50"));
        assertThat(store.increment("app:window", 60, 1, 2, new BigDecimal("0.60")))
                .extracting("requests", "tokens", "amount")
                .containsExactly(2L, 6L, new BigDecimal("1.10"));
        long now = 1_000_000L;
        store.block("app", 5, now);
        assertThat(store.isBlocked("app", now + 4_999)).isTrue();
        assertThat(store.isBlocked("app", now + 5_000)).isFalse();
    }
}

