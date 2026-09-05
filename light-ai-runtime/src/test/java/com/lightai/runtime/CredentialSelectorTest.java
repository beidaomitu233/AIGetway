package com.lightai.runtime.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.pool.SelectionStrategy;
import com.lightai.runtime.credential.CredentialSelector.CredentialView;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * 凭证选择验收（BE-020）：三种策略、健康过滤、限流复位边界、
 * 禁用密钥不入选；无可用返回 CREDENTIAL_NOT_AVAILABLE。
 */
class CredentialSelectorTest {

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    private static CredentialView view(String health, boolean enabled, Instant resetAt,
                                       long concurrency) {
        return new CredentialView(UUID.randomUUID(), UUID.randomUUID(), 50, health,
                resetAt, enabled, concurrency);
    }

    private final RandomGenerator alwaysFirst = new RandomGenerator() {
        @Override
        public double nextDouble() {
            return 0.0;
        }

        @Override
        public long nextLong(long bound) {
            return 0;
        }

        @Override
        public long nextLong() {
            return 0;
        }
    };

    @Test
    void disabledAndInvalidExcluded() {
        CredentialSelector selector = new CredentialSelector(alwaysFirst);
        List<CredentialView> pool = List.of(
                view("INVALID", true, null, 0),
                view("UNAVAILABLE", true, null, 0),
                view("DISABLED", true, null, 0),
                view("UNKNOWN", false, null, 0));
        assertThatThrownBy(() -> selector.select(pool, SelectionStrategy.LEAST_CONCURRENT, NOW))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CREDENTIAL_NOT_AVAILABLE);
    }

    @Test
    void rateLimitedSkippedUntilReset() {
        CredentialSelector selector = new CredentialSelector(alwaysFirst);
        List<CredentialView> pool = List.of(
                view("RATE_LIMITED", true, NOW.plusSeconds(60), 0),
                view("HEALTHY", true, null, 0));
        var handle = selector.select(pool, SelectionStrategy.LEAST_CONCURRENT, NOW);
        assertThat(handle.credentialId()).isEqualTo(pool.get(1).id());
        // 复位时间已过：RATE_LIMITED 重新可用
        List<CredentialView> onlyThrottled = List.of(
                view("RATE_LIMITED", true, NOW.minusSeconds(1), 0));
        var recovered = selector.select(onlyThrottled, SelectionStrategy.LEAST_CONCURRENT, NOW);
        assertThat(recovered.credentialId()).isEqualTo(onlyThrottled.get(0).id());
    }

    @Test
    void leastConcurrentPicksMinimum() {
        CredentialSelector selector = new CredentialSelector(alwaysFirst);
        List<CredentialView> pool = List.of(
                view("HEALTHY", true, null, 5),
                view("HEALTHY", true, null, 1));
        var handle = selector.select(pool, SelectionStrategy.LEAST_CONCURRENT, NOW);
        assertThat(handle.credentialId()).isEqualTo(pool.get(1).id());
    }

    @Test
    void roundRobinRotates() {
        CredentialSelector selector = new CredentialSelector(alwaysFirst);
        List<CredentialView> pool = List.of(
                view("HEALTHY", true, null, 0), view("HEALTHY", true, null, 0));
        var first = selector.select(pool, SelectionStrategy.ROUND_ROBIN, NOW);
        var second = selector.select(pool, SelectionStrategy.ROUND_ROBIN, NOW);
        assertThat(first.credentialId()).isNotEqualTo(second.credentialId());
    }

    @Test
    void healthyPreferredOverUnknown() {
        CredentialSelector selector = new CredentialSelector(alwaysFirst);
        List<CredentialView> pool = List.of(
                view("UNKNOWN", true, null, 0),
                view("HEALTHY", true, null, 0));
        var handle = selector.select(pool, SelectionStrategy.WEIGHTED_RANDOM, NOW);
        assertThat(handle.credentialId()).isEqualTo(pool.get(1).id());
    }
}
