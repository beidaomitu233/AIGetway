package com.lightai.storage.redis;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.capacity.QueueService;
import com.lightai.runtime.capacity.CapacityStore.CapacityStateUnavailableException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Redis 共享 Alias FIFO 队列：跨实例保持唯一全局入队序号。 */
public final class RedisFifoQueue implements QueueService, AutoCloseable {

    private static final String RELEASE_LOCK = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> redis;
    private final String prefix;
    private final int defaultMaxSize;

    public RedisFifoQueue(String redisUri, String namespace, int defaultMaxSize) {
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.redis = connection.sync();
        this.prefix = (namespace == null || namespace.isBlank() ? "light-ai" : namespace) + ":{queue}:";
        this.defaultMaxSize = Math.max(defaultMaxSize, 1);
    }

    @Override
    public QueueTicket enqueue(UUID aliasId, UUID traceId, long deadlineEpochMilli, Instant now) {
        return enqueue(aliasId, traceId, deadlineEpochMilli, now, defaultMaxSize);
    }

    @Override
    public QueueTicket enqueue(UUID aliasId, UUID traceId, long deadlineEpochMilli,
                               Instant now, int maxQueueSize) {
        Objects.requireNonNull(aliasId, "aliasId 不能为空");
        Objects.requireNonNull(traceId, "traceId 不能为空");
        if (deadlineEpochMilli <= now.toEpochMilli()) {
            throw new LightAiException(ErrorCode.QUEUE_TIMEOUT, "等待截止时间已过");
        }
        return locked(() -> {
            cleanup(aliasId, now);
            if (redis.zcard(queueKey(aliasId)) >= Math.max(maxQueueSize, 1)) {
                throw new LightAiException(ErrorCode.QUEUE_FULL, "当前 Alias 队列已满");
            }
            UUID ticketId = UUID.randomUUID();
            long sequence = redis.incr(sequenceKey());
            redis.hset(ticketKey(ticketId), Map.of(
                    "alias", aliasId.toString(), "trace", traceId.toString(),
                    "enqueued_at", now.toString(), "deadline", Long.toString(deadlineEpochMilli)));
            redis.expire(ticketKey(ticketId), Math.max(60,
                    TimeUnit.MILLISECONDS.toSeconds(deadlineEpochMilli - now.toEpochMilli()) + 60));
            redis.zadd(queueKey(aliasId), sequence, ticketId.toString());
            return new QueueTicket(ticketId, aliasId, traceId, now, deadlineEpochMilli);
        });
    }

    @Override
    public AcquireResult tryAcquire(UUID aliasId, Instant now) {
        return new AcquireResult(null, null);
    }

    @Override
    public boolean cancel(UUID ticketId) {
        return remove(ticketId);
    }

    @Override
    public long queueLength(UUID aliasId) {
        return locked(() -> {
            cleanup(aliasId, Instant.now());
            return redis.zcard(queueKey(aliasId));
        });
    }

    @Override
    public boolean isHead(UUID ticketId, Instant now) {
        return locked(() -> {
            Map<String, String> ticket = redis.hgetall(ticketKey(ticketId));
            UUID aliasId = uuid(ticket.get("alias"));
            if (aliasId == null) return false;
            cleanup(aliasId, now);
            List<String> head = redis.zrange(queueKey(aliasId), 0, 0);
            return !head.isEmpty() && ticketId.toString().equals(head.get(0));
        });
    }

    @Override
    public boolean complete(UUID ticketId) {
        return remove(ticketId);
    }

    public boolean health() {
        try { return "PONG".equalsIgnoreCase(redis.ping()); }
        catch (RuntimeException e) { return false; }
    }

    private boolean remove(UUID ticketId) {
        if (ticketId == null) return false;
        return locked(() -> {
            Map<String, String> ticket = redis.hgetall(ticketKey(ticketId));
            UUID aliasId = uuid(ticket.get("alias"));
            if (aliasId == null) return false;
            long removed = redis.zrem(queueKey(aliasId), ticketId.toString());
            redis.del(ticketKey(ticketId));
            return removed == 1;
        });
    }

    private void cleanup(UUID aliasId, Instant now) {
        String queue = queueKey(aliasId);
        for (String ticketId : redis.zrange(queue, 0, -1)) {
            String deadline = redis.hget(ticketKey(UUID.fromString(ticketId)), "deadline");
            if (deadline == null || Long.parseLong(deadline) <= now.toEpochMilli()) {
                redis.zrem(queue, ticketId);
                redis.del(ticketKey(UUID.fromString(ticketId)));
            }
        }
    }

    private <T> T locked(Supplier<T> operation) {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            while (redis.set(lockKey(), token, SetArgs.Builder.nx().px(5_000)) == null) {
                if (System.nanoTime() >= deadline) {
                    throw new CapacityStateUnavailableException("Redis FIFO 队列锁超时");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            return operation.get();
        } catch (RedisException e) {
            throw new CapacityStateUnavailableException("Redis FIFO 队列不可用");
        } finally {
            try { redis.eval(RELEASE_LOCK, ScriptOutputType.INTEGER, new String[]{lockKey()}, token); }
            catch (RuntimeException ignored) { }
        }
    }

    private String queueKey(UUID aliasId) { return prefix + "alias:" + aliasId; }
    private String ticketKey(UUID ticketId) { return prefix + "ticket:" + ticketId; }
    private String sequenceKey() { return prefix + "sequence"; }
    private String lockKey() { return prefix + "lock"; }
    private static UUID uuid(String value) {
        try { return value == null ? null : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
