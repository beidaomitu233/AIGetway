package com.lightai.storage.redis;

import com.lightai.runtime.circuit.CircuitKey;
import com.lightai.runtime.circuit.CircuitPolicy;
import com.lightai.runtime.circuit.CircuitSnapshot;
import com.lightai.runtime.circuit.CircuitStateStore;
import io.lettuce.core.RedisClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCircuitStateStoreIT {

    private String redisUri;
    private String namespace;
    private RedisCircuitStateStore first;
    private RedisCircuitStateStore second;
    private final CircuitPolicy policy = new CircuitPolicy(null, 1, 60, 2, 0.5, 30, 1, 1);

    @BeforeEach
    void setUp() {
        redisUri = System.getProperty("lightai.it.redis-uri", System.getenv("LAI_IT_REDIS_URI"));
        Assumptions.assumeTrue(redisUri != null && !redisUri.isBlank(),
                "未配置 lightai.it.redis-uri/LAI_IT_REDIS_URI");
        namespace = "light-ai-it-" + UUID.randomUUID();
        first = new RedisCircuitStateStore(redisUri, namespace);
        second = new RedisCircuitStateStore(redisUri, namespace);
    }

    @AfterEach
    void tearDown() {
        if (first != null) first.close();
        if (second != null) second.close();
        if (redisUri != null && namespace != null) {
            RedisClient cleanup = RedisClient.create(redisUri);
            try (var connection = cleanup.connect()) {
                var commands = connection.sync();
                var keys = commands.keys(namespace + ":{circuit}:*");
                if (!keys.isEmpty()) commands.del(keys.toArray(String[]::new));
            } finally {
                cleanup.shutdown();
            }
        }
    }

    @Test
    void shouldShareThresholdTransitionAcrossInstances() {
        CircuitKey key = key();
        Instant now = Instant.now();
        assertThat(first.recordResult(key, policy, false, false, now).state())
                .isEqualTo(CircuitSnapshot.STATE_CLOSED);
        assertThat(second.recordResult(key, policy, false, false, now.plusMillis(1)).state())
                .isEqualTo(CircuitSnapshot.STATE_OPEN);

        CircuitSnapshot shared = first.snapshot(key, policy, now.plusMillis(2));
        assertThat(shared.state()).isEqualTo(CircuitSnapshot.STATE_OPEN);
        assertThat(shared.requestCount()).isEqualTo(2);
        assertThat(shared.failureCount()).isEqualTo(2);
    }

    @Test
    void shouldShareHalfOpenProbeQuotaAndReleaseIdempotently() {
        CircuitKey key = key();
        Instant now = Instant.now();
        first.recordResult(key, policy, false, false, now);
        first.recordResult(key, policy, false, false, now.plusMillis(1));
        Instant probeTime = now.plusSeconds(31);
        assertThat(second.snapshot(key, policy, probeTime).state())
                .isEqualTo(CircuitSnapshot.STATE_HALF_OPEN);

        CircuitStateStore.ProbeSlot slot = first.tryAcquireProbe(key, policy, probeTime).orElseThrow();
        assertThat(second.tryAcquireProbe(key, policy, probeTime)).isEmpty();
        second.releaseProbe(slot, key, true, probeTime);
        second.releaseProbe(slot, key, true, probeTime);
        assertThat(first.tryAcquireProbe(key, policy, probeTime)).isPresent();
    }

    @Test
    void shouldApplyManualCommandWithSharedCasVersion() {
        CircuitKey key = key();
        Instant now = Instant.now();
        long version = first.snapshot(key, policy, now).stateVersion();
        var open = new CircuitStateStore.ManualCommand(UUID.randomUUID(),
                CircuitStateStore.ManualCommand.Action.MANUAL_OPEN, version,
                "maintenance", 10, now);
        assertThat(second.applyManualCommand(key, policy, open, now)).isPresent();

        var stale = new CircuitStateStore.ManualCommand(UUID.randomUUID(),
                CircuitStateStore.ManualCommand.Action.MANUAL_RECOVER, version,
                "stale", null, now.plusMillis(1));
        assertThat(first.applyManualCommand(key, policy, stale, now.plusMillis(1))).isEmpty();
        assertThat(first.all()).hasSize(1);
    }

    private static CircuitKey key() {
        return new CircuitKey(UUID.randomUUID(), UUID.randomUUID());
    }
}
