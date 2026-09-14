package com.lightai.storage.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RedisRiskWindowStoreIT {
    private String redisUri;
    private String namespace;
    private RedisRiskWindowStore first;
    private RedisRiskWindowStore second;

    @BeforeEach
    void setUp() {
        redisUri = System.getProperty("lightai.it.redis-uri", System.getenv("LAI_IT_REDIS_URI"));
        Assumptions.assumeTrue(redisUri != null && !redisUri.isBlank(),
                "未配置 lightai.it.redis-uri/LAI_IT_REDIS_URI");
        namespace = "light-ai-risk-it-" + UUID.randomUUID();
        first = new RedisRiskWindowStore(redisUri, namespace);
        second = new RedisRiskWindowStore(redisUri, namespace);
    }

    @AfterEach
    void tearDown() {
        if (first != null) first.close();
        if (second != null) second.close();
        if (redisUri != null && namespace != null) {
            RedisClient cleanup = RedisClient.create(redisUri);
            try (var connection = cleanup.connect()) {
                var keys = connection.sync().keys(namespace + ":{risk}:*");
                if (!keys.isEmpty()) connection.sync().del(keys.toArray(String[]::new));
            } finally {
                cleanup.shutdown();
            }
        }
    }

    @Test
    void sharesAtomicRequestTokenAndAmountWindowAcrossInstances() {
        String key = UUID.randomUUID().toString();
        assertThat(first.increment(key, 60, 1, 4, new BigDecimal("0.50")))
                .extracting("requests", "tokens", "amount")
                .containsExactly(1L, 4L, new BigDecimal("0.50"));
        assertThat(second.increment(key, 60, 1, 2, new BigDecimal("0.60")))
                .extracting("requests", "tokens", "amount")
                .containsExactly(2L, 6L, new BigDecimal("1.10"));
    }

    @Test
    void rejectsBlankWindowKey() {
        Assumptions.assumeTrue(redisUri != null && !redisUri.isBlank());
        assertThatThrownBy(() -> first.increment(" ", 60, 1, 0, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

