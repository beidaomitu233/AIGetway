package com.lightai.storage.redis;

import com.lightai.runtime.ports.RiskWindowStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.math.BigDecimal;
import java.util.List;

/** Redis 原子风险窗口：请求、Token、金额在同一 Lua 脚本中累加并设置 TTL。 */
public final class RedisRiskWindowStore implements RiskWindowStore {
    private static final String INCREMENT = """
            local requests = redis.call('HINCRBY', KEYS[1], 'requests', ARGV[1])
            local tokens = redis.call('HINCRBY', KEYS[1], 'tokens', ARGV[2])
            local amount = redis.call('HINCRBYFLOAT', KEYS[1], 'amount', ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return {requests, tokens, amount}
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> redis;
    private final String prefix;

    public RedisRiskWindowStore(String redisUri) {
        this(redisUri, "light-ai");
    }

    public RedisRiskWindowStore(String redisUri, String namespace) {
        if (redisUri == null || redisUri.isBlank()) throw new IllegalArgumentException("redisUri 不能为空");
        client = RedisClient.create(redisUri);
        connection = client.connect();
        redis = connection.sync();
        prefix = (namespace == null || namespace.isBlank() ? "light-ai" : namespace) + ":{risk}:";
    }

    @Override
    public Window increment(String key, long ttlSeconds, long requestDelta, long tokenDelta, BigDecimal amountDelta) {
        validateKey(key);
        try {
            List<?> values = redis.eval(INCREMENT, ScriptOutputType.MULTI,
                    new String[]{prefix + key}, Long.toString(Math.max(0, requestDelta)),
                    Long.toString(Math.max(0, tokenDelta)),
                    (amountDelta == null ? BigDecimal.ZERO : amountDelta.max(BigDecimal.ZERO)).toPlainString(),
                    Long.toString(Math.max(1, ttlSeconds)));
            if (values == null || values.size() != 3) {
                throw new RiskWindowStore.StateUnavailableException("Redis 风险窗口返回无效状态");
            }
            return new Window(number(values.get(0)), number(values.get(1)), decimal(values.get(2)));
        } catch (RiskWindowStore.StateUnavailableException e) {
            throw e;
        } catch (RedisException e) {
            throw new RiskWindowStore.StateUnavailableException("Redis 风险窗口不可用");
        }
    }

    @Override
    public boolean isBlocked(String key, long nowEpochMillis) {
        validateKey(key);
        try {
            String raw = redis.get(blockKey(key));
            return raw != null && Long.parseLong(raw) > nowEpochMillis;
        } catch (RedisException | NumberFormatException e) {
            throw new RiskWindowStore.StateUnavailableException("Redis 风险阻断状态不可用");
        }
    }

    @Override
    public void block(String key, long ttlSeconds) {
        block(key, ttlSeconds, System.currentTimeMillis());
    }

    @Override
    public void block(String key, long ttlSeconds, long nowEpochMillis) {
        validateKey(key);
        try {
            long ttl = Math.max(1, ttlSeconds);
            redis.setex(blockKey(key), ttl, Long.toString(nowEpochMillis + ttl * 1000L));
        } catch (RedisException e) {
            throw new RiskWindowStore.StateUnavailableException("Redis 风险阻断状态不可用");
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("风险窗口 key 不能为空");
    }

    private String blockKey(String key) {
        return prefix + "blocked:" + key;
    }

    private static long number(Object value) {
        try { return Long.parseLong(String.valueOf(value)); }
        catch (RuntimeException e) { throw new RiskWindowStore.StateUnavailableException("Redis 风险窗口返回无效计数"); }
    }

    private static BigDecimal decimal(Object value) {
        try { return new BigDecimal(String.valueOf(value)); }
        catch (RuntimeException e) { throw new RiskWindowStore.StateUnavailableException("Redis 风险窗口返回无效金额"); }
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
