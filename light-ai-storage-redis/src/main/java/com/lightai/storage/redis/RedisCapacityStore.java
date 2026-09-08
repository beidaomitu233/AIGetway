package com.lightai.storage.redis;

import com.lightai.runtime.capacity.CapacityStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Redis 共享容量存储：三层 RPM/TPM/并发在同一 Lua 中检查与预占。 */
public final class RedisCapacityStore implements CapacityStore, AutoCloseable {

    private static final long WINDOW_SECONDS = 60;
    private static final long COUNTER_TTL_SECONDS = 360;
    private static final long RESERVATION_TTL_SECONDS = 600;

    private static final String RESERVE = """
            local limits = {
              {tonumber(ARGV[6]), tonumber(ARGV[7]), tonumber(ARGV[8])},
              {tonumber(ARGV[9]), tonumber(ARGV[10]), tonumber(ARGV[11])},
              {tonumber(ARGV[12]), tonumber(ARGV[13]), tonumber(ARGV[14])}
            }
            local tokens = tonumber(ARGV[2])
            for i = 1, 3 do
              local key = KEYS[i + 1]
              local lim = limits[i]
              if lim[1] >= 0 and tonumber(redis.call('HGET', key, 'rpm') or '0') + 1 > lim[1] then return i * 10 + 1 end
              if lim[2] >= 0 and tonumber(redis.call('HGET', key, 'tpm') or '0') + tokens > lim[2] then return i * 10 + 2 end
              if lim[3] >= 0 and tonumber(redis.call('HGET', key, 'concurrent') or '0') + 1 > lim[3] then return i * 10 + 3 end
            end
            local active = {}
            for i = 1, 3 do
              local lim = limits[i]
              active[i] = (lim[1] >= 0 or lim[2] >= 0 or lim[3] >= 0) and 1 or 0
              if active[i] == 1 then
                local key = KEYS[i + 1]
                redis.call('HINCRBY', key, 'rpm', 1)
                redis.call('HINCRBY', key, 'tpm', tokens)
                redis.call('HINCRBY', key, 'concurrent', 1)
                redis.call('EXPIRE', key, tonumber(ARGV[4]))
              end
            end
            redis.call('HSET', KEYS[1], 'status', 'ACTIVE', 'window', ARGV[1], 'tokens', ARGV[2],
              's1', KEYS[2], 's2', KEYS[3], 's3', KEYS[4],
              'a1', active[1], 'a2', active[2], 'a3', active[3])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
            redis.call('ZADD', KEYS[5], tonumber(ARGV[3]), KEYS[1])
            return 0
            """;

    private static final String SETTLE = """
            if redis.call('HGET', KEYS[1], 'status') ~= 'ACTIVE' then return 0 end
            local reserved = tonumber(redis.call('HGET', KEYS[1], 'tokens') or '0')
            local actual = tonumber(ARGV[1])
            local sent = tonumber(ARGV[2])
            redis.call('HSET', KEYS[1], 'status', 'SETTLED')
            for i = 1, 3 do
              if tonumber(ARGV[i + 2]) == 1 then
                local key = KEYS[i + 1]
                local concurrent = redis.call('HINCRBY', key, 'concurrent', -1)
                if concurrent < 0 then redis.call('HSET', key, 'concurrent', 0) end
                local tpm = redis.call('HINCRBY', key, 'tpm', actual - reserved)
                if tpm < 0 then redis.call('HSET', key, 'tpm', 0) end
                redis.call('HINCRBY', key, 'tpm_settled', actual)
                if sent == 0 then
                  local rpm = redis.call('HINCRBY', key, 'rpm', -1)
                  if rpm < 0 then redis.call('HSET', key, 'rpm', 0) end
                end
              end
            end
            redis.call('ZREM', KEYS[5], KEYS[1])
            return 1
            """;

    private static final String RELEASE = """
            if redis.call('HGET', KEYS[1], 'status') ~= 'ACTIVE' then return 0 end
            local reserved = tonumber(redis.call('HGET', KEYS[1], 'tokens') or '0')
            redis.call('HSET', KEYS[1], 'status', 'RELEASED')
            for i = 1, 3 do
              if tonumber(ARGV[i]) == 1 then
                local key = KEYS[i + 1]
                local rpm = redis.call('HINCRBY', key, 'rpm', -1)
                local tpm = redis.call('HINCRBY', key, 'tpm', -reserved)
                local concurrent = redis.call('HINCRBY', key, 'concurrent', -1)
                if rpm < 0 then redis.call('HSET', key, 'rpm', 0) end
                if tpm < 0 then redis.call('HSET', key, 'tpm', 0) end
                if concurrent < 0 then redis.call('HSET', key, 'concurrent', 0) end
              end
            end
            redis.call('ZREM', KEYS[5], KEYS[1])
            return 1
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> redis;
    private final String prefix;
    private final long leaseMillis;

    public RedisCapacityStore(String redisUri) {
        this(redisUri, "light-ai", 150_000L);
    }

    public RedisCapacityStore(String redisUri, String namespace, long leaseMillis) {
        if (redisUri == null || redisUri.isBlank()) throw new IllegalArgumentException("redisUri 不能为空");
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.redis = connection.sync();
        this.prefix = (namespace == null || namespace.isBlank() ? "light-ai" : namespace) + ":{capacity}:";
        this.leaseMillis = Math.max(leaseMillis, 1L);
    }

    @Override
    public ReservationHandle reserve(ReserveRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        requireScopeIds(request);
        return reserveScopes(
                new String[]{"alias", "provider_model", "credential"},
                new UUID[]{request.aliasId(), request.providerModelId(), request.credentialId()},
                new ScopeLimit[]{request.aliasLimit(), request.providerModelLimit(), request.credentialLimit()},
                request.estimatedTokens(), request.maxTokens());
    }

    @Override
    public ReservationHandle reserveApplication(
            UUID applicationId, UUID applicationKeyId, long estimatedTokens,
            ScopeLimit applicationLimit, ScopeLimit applicationKeyLimit) {
        if (applicationId == null || applicationKeyId == null) {
            throw new IllegalArgumentException("应用容量预占 scope id 不能为空");
        }
        return reserveScopes(
                new String[]{"application", "application_key", "noop"},
                new UUID[]{applicationId, applicationKeyId, null},
                new ScopeLimit[]{applicationLimit, applicationKeyLimit, null},
                estimatedTokens, 0);
    }

    private ReservationHandle reserveScopes(
            String[] scopeTypes, UUID[] scopeIds, ScopeLimit[] limits,
            long estimatedTokens, long maxTokens) {
        long window = windowStart(Instant.now());
        long tokens = Math.max(estimatedTokens, 0) + Math.max(maxTokens, 0);
        UUID reservationId = UUID.randomUUID();
        String reservationKey = reservationKey(reservationId);
        String[] keys = {
                reservationKey,
                counterKeyOrNoop(scopeTypes[0], scopeIds[0], window),
                counterKeyOrNoop(scopeTypes[1], scopeIds[1], window),
                counterKeyOrNoop(scopeTypes[2], scopeIds[2], window),
                reservationsKey()
        };
        List<String> args = new ArrayList<>();
        args.add(Long.toString(window));
        args.add(Long.toString(tokens));
        args.add(Long.toString(System.currentTimeMillis() + leaseMillis));
        args.add(Long.toString(COUNTER_TTL_SECONDS));
        args.add(Long.toString(RESERVATION_TTL_SECONDS));
        addLimit(args, limits[0]);
        addLimit(args, limits[1]);
        addLimit(args, limits[2]);
        try {
            Number result = redis.eval(RESERVE, ScriptOutputType.INTEGER, keys, args.toArray(String[]::new));
            long code = result == null ? -1 : result.longValue();
            if (code != 0) {
                throw limited(code, scopeTypes);
            }
            return new ReservationHandle(reservationId, window, tokens);
        } catch (CapacityLimitedException e) {
            throw e;
        } catch (RedisException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void settle(UUID reservationId, long actualTokens, boolean requestSent) {
        terminal(reservationId, SETTLE,
                Long.toString(Math.max(actualTokens, 0)), requestSent ? "1" : "0");
    }

    @Override
    public void release(UUID reservationId) {
        terminal(reservationId, RELEASE);
    }

    @Override
    public UsageSnapshot usage(String scopeType, UUID scopeId) {
        long window = windowStart(Instant.now());
        try {
            String key = counterKey(scopeType, scopeId, window);
            return new UsageSnapshot(window,
                    number(redis.hget(key, "rpm")), number(redis.hget(key, "tpm")),
                    number(redis.hget(key, "tpm_settled")), number(redis.hget(key, "concurrent")));
        } catch (RedisException e) {
            throw unavailable(e);
        }
    }

    @Override
    public boolean health() {
        try {
            return "PONG".equalsIgnoreCase(redis.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public int reclaimExpired(Instant now) {
        try {
            List<String> expired = redis.zrangebyscore(reservationsKey(), 0, now.toEpochMilli());
            int reclaimed = 0;
            for (String key : expired) {
                Number result = terminalKey(key, RELEASE);
                if (result != null && result.longValue() == 1L) reclaimed++;
            }
            return reclaimed;
        } catch (RedisException e) {
            throw unavailable(e);
        }
    }

    private Number terminal(UUID reservationId, String script, String... leadingArgs) {
        Objects.requireNonNull(reservationId, "reservationId 不能为空");
        return terminalKey(reservationKey(reservationId), script, leadingArgs);
    }

    private Number terminalKey(String reservationKey, String script, String... leadingArgs) {
        try {
            List<String> scopeData = redis.hmget(reservationKey, "s1", "s2", "s3", "a1", "a2", "a3")
                    .stream().map(value -> value.hasValue() ? value.getValue() : "").toList();
            if (scopeData.stream().allMatch(String::isEmpty)) return 0L;
            String[] keys = {reservationKey, keyOrNoop(scopeData.get(0)), keyOrNoop(scopeData.get(1)),
                    keyOrNoop(scopeData.get(2)), reservationsKey()};
            List<String> args = new ArrayList<>(List.of(leadingArgs));
            args.add(scopeData.get(3).isEmpty() ? "0" : scopeData.get(3));
            args.add(scopeData.get(4).isEmpty() ? "0" : scopeData.get(4));
            args.add(scopeData.get(5).isEmpty() ? "0" : scopeData.get(5));
            return redis.eval(script, ScriptOutputType.INTEGER, keys, args.toArray(String[]::new));
        } catch (RedisException e) {
            throw unavailable(e);
        }
    }

    private String keyOrNoop(String key) {
        return key == null || key.isBlank() ? prefix + "noop" : key;
    }

    private String counterKeyOrNoop(String type, UUID id, long window) {
        return id == null ? prefix + "noop" : counterKey(type, id, window);
    }

    private static void addLimit(List<String> args, ScopeLimit limit) {
        args.add(limit == null || limit.rpmLimit() == null ? "-1" : limit.rpmLimit().toString());
        args.add(limit == null || limit.tpmLimit() == null ? "-1" : limit.tpmLimit().toString());
        args.add(limit == null || limit.concurrentLimit() == null ? "-1" : limit.concurrentLimit().toString());
    }

    private static void requireScopeIds(ReserveRequest request) {
        if (request.aliasId() == null || request.providerModelId() == null || request.credentialId() == null) {
            throw new IllegalArgumentException("容量预占三层 scope id 均不能为空");
        }
    }

    private static String limitMessage(long code) {
        String[] scopes = {"", "Alias", "ProviderModel", "Credential"};
        String[] metrics = {"", "RPM", "TPM", "并发"};
        int scope = (int) (code / 10);
        int metric = (int) (code % 10);
        return (scope < scopes.length ? scopes[scope] : "容量") + " "
                + (metric < metrics.length ? metrics[metric] : "限额") + " 不足";
    }

    private static CapacityLimitedException limited(long code, String[] requestedScopes) {
        String[] metrics = {"", "RPM", "TPM", "CONCURRENT"};
        int scope = (int) (code / 10);
        int metric = (int) (code % 10);
        if (scope > 0 && scope <= requestedScopes.length && metric > 0 && metric < metrics.length) {
            return new CapacityLimitedException(requestedScopes[scope - 1], metrics[metric]);
        }
        return new CapacityLimitedException(limitMessage(code));
    }

    private static long number(String value) {
        if (value == null || value.isBlank()) return 0L;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0L; }
    }

    private String reservationKey(UUID id) { return prefix + "reservation:" + id; }
    private String reservationsKey() { return prefix + "reservations"; }
    private String counterKey(String type, UUID id, long window) {
        return prefix + "counter:" + type.toLowerCase(java.util.Locale.ROOT) + ":" + id + ":" + window;
    }
    private static long windowStart(Instant now) {
        return now.getEpochSecond() / WINDOW_SECONDS * WINDOW_SECONDS;
    }
    private static CapacityStateUnavailableException unavailable(Exception cause) {
        return new CapacityStateUnavailableException("Redis 共享容量状态不可用");
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
