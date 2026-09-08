package com.lightai.storage.redis;

import com.lightai.runtime.circuit.CircuitKey;
import com.lightai.runtime.circuit.CircuitPolicy;
import com.lightai.runtime.circuit.CircuitSnapshot;
import com.lightai.runtime.circuit.CircuitStateStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Redis 共享熔断状态：状态迁移、探测名额与人工命令 CAS 在分布式锁内序列化。 */
public final class RedisCircuitStateStore implements CircuitStateStore, AutoCloseable {

    private static final String RELEASE_LOCK = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> redis;
    private final String prefix;
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    public RedisCircuitStateStore(String redisUri) {
        this(redisUri, "light-ai");
    }

    public RedisCircuitStateStore(String redisUri, String namespace) {
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.redis = connection.sync();
        this.prefix = (namespace == null || namespace.isBlank() ? "light-ai" : namespace) + ":{circuit}:";
    }

    public void addListener(EventListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener 不能为空"));
    }

    @Override
    public CircuitSnapshot snapshot(CircuitKey key, CircuitPolicy policy, Instant now) {
        return locked(() -> {
            State state = state(key, now);
            lazyTransition(state, policy, now);
            save(state);
            return state.snapshot();
        });
    }

    @Override
    public CircuitSnapshot recordResult(CircuitKey key, CircuitPolicy policy,
                                        boolean success, boolean throttled, Instant now) {
        return locked(() -> {
            State state = state(key, now);
            lazyTransition(state, policy, now);
            state.ensureWindow(policy, now);
            if (!throttled) {
                state.requestCount++;
                if (!success) state.failureCount++;
                if (CircuitSnapshot.STATE_HALF_OPEN.equals(state.state)) {
                    if (success) {
                        state.probeSuccessCount++;
                        if (state.probeSuccessCount >= policy.halfOpenSuccesses()) {
                            transition(state, policy, CircuitSnapshot.STATE_CLOSED,
                                    "PROBE_SUCCESS", null, null, now);
                        }
                    } else {
                        transition(state, policy, CircuitSnapshot.STATE_OPEN,
                                "PROBE_FAILURE", null, null, now);
                    }
                } else if (CircuitSnapshot.STATE_CLOSED.equals(state.state)
                        && state.requestCount >= policy.minRequests()
                        && (double) state.failureCount / state.requestCount >= policy.failureRate()) {
                    transition(state, policy, CircuitSnapshot.STATE_OPEN,
                            "AUTO_THRESHOLD", null, null, now);
                }
            }
            save(state);
            return state.snapshot();
        });
    }

    @Override
    public Optional<ProbeSlot> tryAcquireProbe(CircuitKey key, CircuitPolicy policy, Instant now) {
        return locked(() -> {
            State state = state(key, now);
            lazyTransition(state, policy, now);
            if (!CircuitSnapshot.STATE_HALF_OPEN.equals(state.state)
                    || state.probeInflight >= policy.halfOpenProbes()) {
                save(state);
                return Optional.empty();
            }
            ProbeSlot slot = new ProbeSlot(UUID.randomUUID(), key);
            if (redis.sadd(probeKey(key), slot.slotId().toString()) == 1L) {
                state.probeInflight++;
                save(state);
                return Optional.of(slot);
            }
            return Optional.empty();
        });
    }

    @Override
    public void releaseProbe(ProbeSlot slot, CircuitKey key, boolean success, Instant now) {
        if (slot == null) return;
        locked(() -> {
            State state = state(key, now);
            if (redis.srem(probeKey(key), slot.slotId().toString()) == 1L && state.probeInflight > 0) {
                state.probeInflight--;
                save(state);
            }
            return null;
        });
    }

    @Override
    public Optional<CircuitSnapshot> applyManualCommand(CircuitKey key, CircuitPolicy policy,
                                                        ManualCommand command, Instant now) {
        return locked(() -> {
            State state = state(key, now);
            lazyTransition(state, policy, now);
            if (state.stateVersion != command.expectedStateVersion()) return Optional.empty();
            if (command.action() == ManualCommand.Action.MANUAL_OPEN) {
                int openSeconds = command.openSeconds() == null ? policy.openSeconds() : command.openSeconds();
                CircuitPolicy effective = new CircuitPolicy(policy.policyId(), policy.snapshotNo(),
                        policy.windowSeconds(), policy.minRequests(), policy.failureRate(), openSeconds,
                        policy.halfOpenProbes(), policy.halfOpenSuccesses());
                transition(state, effective, CircuitSnapshot.STATE_OPEN,
                        "MANUAL_OPEN", command.commandId(), command.reason(), now);
            } else if (!CircuitSnapshot.STATE_CLOSED.equals(state.state)) {
                transition(state, policy, CircuitSnapshot.STATE_CLOSED,
                        "MANUAL_RECOVER", command.commandId(), command.reason(), now);
            }
            state.lastAppliedCommandId = command.commandId();
            save(state);
            return Optional.of(state.snapshot());
        });
    }

    @Override
    public List<CircuitSnapshot> all() {
        return locked(() -> redis.smembers(indexKey()).stream()
                .map(redis::hgetall).filter(values -> !values.isEmpty())
                .map(State::from).map(State::snapshot).toList());
    }

    public boolean health() {
        try { return "PONG".equalsIgnoreCase(redis.ping()); }
        catch (RuntimeException e) { return false; }
    }

    private void lazyTransition(State state, CircuitPolicy policy, Instant now) {
        if (CircuitSnapshot.STATE_OPEN.equals(state.state)
                && state.nextProbeAt != null && !now.isBefore(state.nextProbeAt)) {
            transition(state, policy, CircuitSnapshot.STATE_HALF_OPEN,
                    "OPEN_EXPIRED", null, null, now);
            state.probeSuccessCount = 0;
            state.probeInflight = 0;
            redis.del(probeKey(state.key));
        }
    }

    private void transition(State state, CircuitPolicy policy, String toState,
                            String trigger, UUID commandId, String reason, Instant now) {
        String from = state.state;
        if (from.equals(toState)) return;
        state.state = toState;
        state.stateVersion++;
        if (CircuitSnapshot.STATE_OPEN.equals(toState)) {
            state.openedAt = now;
            state.nextProbeAt = now.plusSeconds(policy.openSeconds());
            state.openSource = trigger.startsWith("MANUAL") ? "MANUAL" : "AUTO";
            state.lastReason = reason;
        } else if (CircuitSnapshot.STATE_CLOSED.equals(toState)) {
            state.windowStartedAt = now;
            state.requestCount = 0;
            state.failureCount = 0;
            state.probeInflight = 0;
            state.probeSuccessCount = 0;
            state.openedAt = null;
            state.nextProbeAt = null;
            redis.del(probeKey(state.key));
        }
        CircuitEventPayload event = new CircuitEventPayload(
                state.circuitId + ":" + state.stateVersion, state.key, from, toState,
                trigger, commandId, null, reason, now);
        listeners.forEach(listener -> listener.onEvent(event));
    }

    private State state(CircuitKey key, Instant now) {
        requireKey(key);
        Map<String, String> values = redis.hgetall(stateKey(key));
        if (!values.isEmpty()) return State.from(values);
        State state = new State();
        state.circuitId = UUID.randomUUID();
        state.key = key;
        state.state = CircuitSnapshot.STATE_CLOSED;
        state.stateVersion = 1;
        state.windowStartedAt = now;
        return state;
    }

    private void save(State state) {
        String key = stateKey(state.key);
        redis.hset(key, state.values());
        redis.sadd(indexKey(), key);
    }

    private <T> T locked(Supplier<T> operation) {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            while (redis.set(lockKey(), token, SetArgs.Builder.nx().px(5_000)) == null) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Redis 共享熔断状态锁超时");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            return operation.get();
        } catch (RedisException e) {
            throw new IllegalStateException("Redis 共享熔断状态不可用");
        } finally {
            try { redis.eval(RELEASE_LOCK, ScriptOutputType.INTEGER, new String[]{lockKey()}, token); }
            catch (RuntimeException ignored) { }
        }
    }

    private String stateKey(CircuitKey key) { return prefix + "state:" + key.providerModelId() + ":" + key.credentialId(); }
    private String probeKey(CircuitKey key) { return prefix + "probes:" + key.providerModelId() + ":" + key.credentialId(); }
    private String indexKey() { return prefix + "states"; }
    private String lockKey() { return prefix + "lock"; }
    private static void requireKey(CircuitKey key) {
        if (key == null || key.providerModelId() == null || key.credentialId() == null) {
            throw new IllegalArgumentException("熔断键的 providerModelId/credentialId 不能为空");
        }
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    private static final class State {
        UUID circuitId;
        CircuitKey key;
        String state;
        long stateVersion;
        Instant windowStartedAt;
        int requestCount;
        int failureCount;
        int probeInflight;
        int probeSuccessCount;
        Instant openedAt;
        Instant nextProbeAt;
        String openSource;
        String lastReason;
        UUID lastAppliedCommandId;

        void ensureWindow(CircuitPolicy policy, Instant now) {
            if (windowStartedAt == null || !now.isBefore(windowStartedAt.plusSeconds(policy.windowSeconds()))) {
                windowStartedAt = now;
                requestCount = 0;
                failureCount = 0;
            }
        }

        CircuitSnapshot snapshot() {
            return new CircuitSnapshot(circuitId, key, state, stateVersion, windowStartedAt,
                    requestCount, failureCount, probeInflight, probeSuccessCount,
                    openedAt, nextProbeAt, openSource, lastReason, lastAppliedCommandId);
        }

        Map<String, String> values() {
            java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
            put(values, "circuit_id", circuitId); put(values, "model_id", key.providerModelId());
            put(values, "credential_id", key.credentialId()); put(values, "state", state);
            put(values, "version", stateVersion); put(values, "window_at", windowStartedAt);
            put(values, "requests", requestCount); put(values, "failures", failureCount);
            put(values, "probe_inflight", probeInflight); put(values, "probe_success", probeSuccessCount);
            put(values, "opened_at", openedAt); put(values, "next_probe_at", nextProbeAt);
            put(values, "open_source", openSource); put(values, "last_reason", lastReason);
            put(values, "last_command_id", lastAppliedCommandId);
            return values;
        }

        static State from(Map<String, String> values) {
            State state = new State();
            state.circuitId = uuid(values.get("circuit_id"));
            state.key = new CircuitKey(uuid(values.get("model_id")), uuid(values.get("credential_id")));
            state.state = values.get("state"); state.stateVersion = number(values.get("version"));
            state.windowStartedAt = instant(values.get("window_at"));
            state.requestCount = (int) number(values.get("requests"));
            state.failureCount = (int) number(values.get("failures"));
            state.probeInflight = (int) number(values.get("probe_inflight"));
            state.probeSuccessCount = (int) number(values.get("probe_success"));
            state.openedAt = instant(values.get("opened_at"));
            state.nextProbeAt = instant(values.get("next_probe_at"));
            state.openSource = values.get("open_source"); state.lastReason = values.get("last_reason");
            state.lastAppliedCommandId = uuid(values.get("last_command_id"));
            return state;
        }

        private static void put(Map<String, String> values, String key, Object value) {
            if (value != null) values.put(key, value.toString());
        }
        private static long number(String value) { return value == null ? 0 : Long.parseLong(value); }
        private static UUID uuid(String value) { return value == null || value.isBlank() ? null : UUID.fromString(value); }
        private static Instant instant(String value) { return value == null || value.isBlank() ? null : Instant.parse(value); }
    }
}
