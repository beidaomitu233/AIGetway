package com.lightai.storage.redis;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import io.lettuce.core.RedisClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisFifoQueueIT {

    private String redisUri;
    private String namespace;
    private RedisFifoQueue first;
    private RedisFifoQueue second;

    @BeforeEach
    void setUp() {
        redisUri = System.getProperty("lightai.it.redis-uri", System.getenv("LAI_IT_REDIS_URI"));
        Assumptions.assumeTrue(redisUri != null && !redisUri.isBlank(),
                "未配置 lightai.it.redis-uri/LAI_IT_REDIS_URI");
        namespace = "light-ai-it-" + UUID.randomUUID();
        first = new RedisFifoQueue(redisUri, namespace, 10);
        second = new RedisFifoQueue(redisUri, namespace, 10);
    }

    @AfterEach
    void tearDown() {
        if (first != null) first.close();
        if (second != null) second.close();
        if (redisUri != null && namespace != null) {
            RedisClient cleanup = RedisClient.create(redisUri);
            try (var connection = cleanup.connect()) {
                var commands = connection.sync();
                var keys = commands.keys(namespace + ":{queue}:*");
                if (!keys.isEmpty()) commands.del(keys.toArray(String[]::new));
            } finally {
                cleanup.shutdown();
            }
        }
    }

    @Test
    void shouldKeepGlobalFifoOrderAcrossInstances() {
        UUID alias = UUID.randomUUID();
        Instant now = Instant.now();
        var one = first.enqueue(alias, UUID.randomUUID(), now.plusSeconds(30).toEpochMilli(), now);
        var two = second.enqueue(alias, UUID.randomUUID(), now.plusSeconds(30).toEpochMilli(), now);
        var three = first.enqueue(alias, UUID.randomUUID(), now.plusSeconds(30).toEpochMilli(), now);

        assertThat(second.isHead(one.ticketId(), now)).isTrue();
        assertThat(first.isHead(two.ticketId(), now)).isFalse();
        assertThat(second.complete(one.ticketId())).isTrue();
        assertThat(first.isHead(two.ticketId(), now)).isTrue();
        assertThat(first.complete(two.ticketId())).isTrue();
        assertThat(second.isHead(three.ticketId(), now)).isTrue();
        assertThat(second.queueLength(alias)).isEqualTo(1);
    }

    @Test
    void shouldEnforceEffectiveQueueLimitAndShareCancellation() {
        UUID alias = UUID.randomUUID();
        Instant now = Instant.now();
        var ticket = first.enqueue(alias, UUID.randomUUID(), now.plusSeconds(30).toEpochMilli(), now, 1);

        assertThatThrownBy(() -> second.enqueue(
                alias, UUID.randomUUID(), now.plusSeconds(30).toEpochMilli(), now, 1))
                .isInstanceOfSatisfying(LightAiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.QUEUE_FULL));
        assertThat(second.cancel(ticket.ticketId())).isTrue();
        assertThat(first.queueLength(alias)).isZero();
    }

    @Test
    void shouldRemoveExpiredHeadBeforeServingNextTicket() {
        UUID alias = UUID.randomUUID();
        Instant now = Instant.now();
        var expired = first.enqueue(alias, UUID.randomUUID(), now.plusMillis(5).toEpochMilli(), now);
        var next = second.enqueue(alias, UUID.randomUUID(), now.plusSeconds(30).toEpochMilli(), now);

        Instant later = now.plusMillis(10);
        assertThat(first.isHead(expired.ticketId(), later)).isFalse();
        assertThat(second.isHead(next.ticketId(), later)).isTrue();
        assertThat(first.queueLength(alias)).isEqualTo(1);
        assertThat(second.health()).isTrue();
    }
}
