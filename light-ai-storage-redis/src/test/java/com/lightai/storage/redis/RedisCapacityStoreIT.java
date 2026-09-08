package com.lightai.storage.redis;

import com.lightai.runtime.capacity.CapacityStore;
import io.lettuce.core.RedisClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCapacityStoreIT {

    private String redisUri;
    private String namespace;
    private RedisCapacityStore first;
    private RedisCapacityStore second;

    @BeforeEach
    void setUp() {
        redisUri = System.getProperty("lightai.it.redis-uri", System.getenv("LAI_IT_REDIS_URI"));
        Assumptions.assumeTrue(redisUri != null && !redisUri.isBlank(),
                "未配置 lightai.it.redis-uri/LAI_IT_REDIS_URI");
        namespace = "light-ai-it-" + UUID.randomUUID();
        first = new RedisCapacityStore(redisUri, namespace, 120_000L);
        second = new RedisCapacityStore(redisUri, namespace, 120_000L);
    }

    @AfterEach
    void tearDown() {
        if (first != null) first.close();
        if (second != null) second.close();
        if (redisUri != null && namespace != null) {
            RedisClient cleanup = RedisClient.create(redisUri);
            try (var connection = cleanup.connect()) {
                var commands = connection.sync();
                var keys = commands.keys(namespace + ":{capacity}:*");
                if (!keys.isEmpty()) commands.del(keys.toArray(String[]::new));
            } finally {
                cleanup.shutdown();
            }
        }
    }

    @Test
    void shouldShareAtomicLimitsAcrossInstancesAndReleaseIdempotently() {
        UUID alias = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        CapacityStore.ScopeLimit aliasLimit = new CapacityStore.ScopeLimit(1L, 100L, 1);
        CapacityStore.ReserveRequest request = new CapacityStore.ReserveRequest(
                alias, model, credential, 10, 5, aliasLimit, null, null);

        var reservation = first.reserve(request);
        assertThatThrownBy(() -> second.reserve(request))
                .isInstanceOf(CapacityStore.CapacityLimitedException.class);

        second.release(reservation.reservationId());
        second.release(reservation.reservationId());
        var next = second.reserve(request);
        second.release(next.reservationId());

        assertThat(first.usage("alias", alias).concurrentActive()).isZero();
        assertThat(first.usage("alias", alias).rpmReserved()).isZero();
    }

    @Test
    void shouldApplyNoPartialCountersWhenAnyScopeRejects() {
        UUID alias = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        CapacityStore.ReserveRequest request = new CapacityStore.ReserveRequest(
                alias, model, credential, 10, 0,
                new CapacityStore.ScopeLimit(10L, 100L, 10),
                new CapacityStore.ScopeLimit(null, null, 0), null);

        assertThatThrownBy(() -> first.reserve(request))
                .isInstanceOf(CapacityStore.CapacityLimitedException.class);
        assertThat(second.usage("alias", alias).rpmReserved()).isZero();
        assertThat(second.usage("alias", alias).tpmReserved()).isZero();
        assertThat(second.usage("alias", alias).concurrentActive()).isZero();
    }

    @Test
    void shouldSettleInOriginalWindowExactlyOnce() {
        UUID alias = UUID.randomUUID();
        UUID model = UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        CapacityStore.ReserveRequest request = new CapacityStore.ReserveRequest(
                alias, model, credential, 10, 5,
                new CapacityStore.ScopeLimit(10L, 100L, 10), null, null);

        var reservation = first.reserve(request);
        second.settle(reservation.reservationId(), 7, true);
        first.settle(reservation.reservationId(), 99, true);

        var usage = second.usage("alias", alias);
        assertThat(usage.windowStartEpochSecond()).isEqualTo(reservation.windowStartEpochSecond());
        assertThat(usage.rpmReserved()).isEqualTo(1);
        assertThat(usage.tpmReserved()).isEqualTo(7);
        assertThat(usage.tpmSettled()).isEqualTo(7);
        assertThat(usage.concurrentActive()).isZero();
    }

    @Test
    void shouldReclaimExpiredReservation() {
        UUID alias = UUID.randomUUID();
        RedisCapacityStore shortLease = new RedisCapacityStore(redisUri, namespace, 1L);
        try {
            shortLease.reserve(new CapacityStore.ReserveRequest(
                    alias, UUID.randomUUID(), UUID.randomUUID(), 1, 0,
                    new CapacityStore.ScopeLimit(10L, 10L, 1), null, null));
            assertThat(second.reclaimExpired(Instant.now().plusSeconds(1))).isEqualTo(1);
            assertThat(first.usage("alias", alias).concurrentActive()).isZero();
        } finally {
            shortLease.close();
        }
    }

    @Test
    void shouldReportRedisHealth() {
        assertThat(first.health()).isTrue();
    }
}
