package com.lightai.admin.publish;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lightai.client.json.ProtocolJson;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.capacity.CapacityStore;
import com.lightai.runtime.circuit.CircuitPolicy;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * ConfigSnapshotPort 的 JDBC 实现：从最近 ACTIVE config_snapshot.content 反序列化为 ActiveSnapshot。
 *
 * <p>BE-P07 激活事务完成后，{@link #invalidate()} 让 Runtime 在下一次 active() 时拿新快照。
 * Embedded 模式与 Standalone Server 均通过该端口读取发布配置；未装配此 Bean 时 Runtime 只能拿到空快照。
 *
 * <p>数据方言自适应：在事务外打开连接、读时缓存 + 失效；连接不可用时返回空快照，调用方按别名未找到处理。
 */
public final class JdbcConfigSnapshotPortAdapter extends AbstractJdbcRepository implements ConfigSnapshotPort {

    private final Supplier<Connection> connectionSupplier;
    private final AtomicReference<CachedSnapshot> cache = new AtomicReference<>();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = ProtocolJson.protocol();

    public JdbcConfigSnapshotPortAdapter(String schemaName, DataSource dataSource) {
        super(schemaName);
        this.connectionSupplier = () -> {
            try {
                return dataSource.getConnection();
            } catch (SQLException e) {
                throw new IllegalStateException("无法获取数据库连接", e);
            }
        };
    }

    public JdbcConfigSnapshotPortAdapter(String schemaName, Supplier<Connection> connectionSupplier) {
        super(schemaName);
        this.connectionSupplier = connectionSupplier;
    }

    @Override
    public ActiveSnapshot active() {
        long currentMax = readMaxSnapshotNo();
        CachedSnapshot cached = cache.get();
        if (cached != null && currentMax > 0 && cached.maxSnapshotNo == currentMax) {
            return cached.snapshot;
        }
        ActiveSnapshot fresh = readActiveSnapshot();
        cache.set(new CachedSnapshot(currentMax, fresh));
        return fresh;
    }

    /** 在发布激活完成后调用，让 Runtime 下次访问时读取新快照。 */
    public void invalidate() {
        cache.set(null);
    }

    /** 库中确实存在 ACTIVE 快照行才视为可用；空库或连接不可用时不假装就绪。 */
    @Override
    public boolean hasActiveSnapshot() {
        if (connectionSupplier == null) {
            return false;
        }
        try (Connection connection = connectionSupplier.get()) {
            return latestActiveSnapshotNo(connection).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<Long> latestActiveSnapshotNo(Connection connection) throws SQLException {
        String sql = "SELECT snapshot_no FROM " + qualify(connection, "config_snapshot")
                + " WHERE status = 'ACTIVE' ORDER BY snapshot_no DESC LIMIT 1";
        try (var statement = connection.prepareStatement(sql);
             var rs = statement.executeQuery()) {
            return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
        }
    }

    private long readMaxSnapshotNo() {
        if (connectionSupplier == null) return 0L;
        try (Connection connection = connectionSupplier.get()) {
            String sql = "SELECT max(snapshot_no) FROM " + qualify(connection, "config_snapshot")
                    + " WHERE status = 'ACTIVE'";
            try (var statement = connection.prepareStatement(sql);
                 var rs = statement.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? 0L : v;
                }
            }
        } catch (Exception ignored) {
            // 表或连接不可用时按零处理。
        }
        return 0L;
    }

    private ActiveSnapshot readActiveSnapshot() {
        if (connectionSupplier == null) return new ActiveSnapshot(0L, List.of());
        Optional<RawSnapshot> latest;
        try (Connection connection = connectionSupplier.get()) {
            latest = loadLatestActive(connection);
        } catch (Exception e) {
            return new ActiveSnapshot(0L, List.of());
        }
        if (latest.isEmpty()) {
            return new ActiveSnapshot(0L, List.of());
        }
        RawSnapshot raw = latest.get();
        Map<String, Object> content = parseContent(raw.contentJson);
        List<AliasView> aliases = parseAliases(content);
        return new ActiveSnapshot(raw.snapshotNo, aliases, parseCapacityLimits(content),
                parseCircuitPolicies(content, raw.snapshotNo), parseQueuePolicies(content));
    }

    Map<String, ConfigSnapshotPort.QueuePolicy> parseQueuePolicies(Map<String, Object> content) {
        Map<String, ConfigSnapshotPort.QueuePolicy> result = new LinkedHashMap<>();
        Object policiesObj = content.get("limit_policies");
        if (!(policiesObj instanceof List<?> policies)) return Map.of();
        for (Object item : policies) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> policy = (Map<String, Object>) raw;
            if (!Boolean.TRUE.equals(toBoolOrNull(policy.get("enabled")))) continue;
            String scopeType = toString(policy.get("scope_type"));
            String scopeId = toString(policy.get("scope_id"));
            if (scopeType == null || scopeId == null) continue;
            String overflow = toString(policy.get("overflow_strategy"));
            result.put(scopeType.toUpperCase(java.util.Locale.ROOT) + ":" + scopeId,
                    new ConfigSnapshotPort.QueuePolicy(overflow,
                            Math.max(0, toLong(policy.get("queue_timeout_ms"))),
                            Math.max(0, toInt(policy.get("queue_max_size")))));
        }
        return Map.copyOf(result);
    }

    Map<String, CircuitPolicy> parseCircuitPolicies(Map<String, Object> content, long snapshotNo) {
        Map<String, CircuitPolicy> result = new LinkedHashMap<>();
        Object policiesObj = content.get("reliability_policies");
        if (!(policiesObj instanceof List<?> policies)) return Map.of();
        for (Object item : policies) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> policy = (Map<String, Object>) raw;
            if (!Boolean.TRUE.equals(toBoolOrNull(policy.get("enabled")))) continue;
            String aliasId = toString(policy.get("alias_id"));
            if (aliasId == null) continue;
            result.put(aliasId, new CircuitPolicy(
                    toUuidOrNull(policy.get("id")), snapshotNo,
                    positiveOrDefault(toIntOrNull(policy.get("circuit_window_seconds")), 60),
                    positiveOrDefault(toIntOrNull(policy.get("circuit_min_requests")), 20),
                    decimalOrDefault(policy.get("circuit_failure_rate"), 0.5),
                    positiveOrDefault(toIntOrNull(policy.get("circuit_open_seconds")), 30),
                    positiveOrDefault(toIntOrNull(policy.get("circuit_half_open_probes")), 3),
                    positiveOrDefault(toIntOrNull(policy.get("circuit_half_open_successes")), 2)));
        }
        return Map.copyOf(result);
    }

    Map<String, CapacityStore.ScopeLimit> parseCapacityLimits(Map<String, Object> content) {
        Map<String, CapacityStore.ScopeLimit> result = new LinkedHashMap<>();
        Object policiesObj = content.get("limit_policies");
        if (policiesObj instanceof List<?> policies) {
            for (Object item : policies) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> policy = (Map<String, Object>) raw;
                if (!Boolean.TRUE.equals(toBoolOrNull(policy.get("enabled")))) continue;
                String scopeType = toString(policy.get("scope_type"));
                String scopeId = toString(policy.get("scope_id"));
                if (scopeType == null || scopeId == null) continue;
                result.put(scopeType.toUpperCase(java.util.Locale.ROOT) + ":" + scopeId,
                        readLimit(policy));
            }
        }

        Object credentialsObj = content.get("credentials");
        if (credentialsObj instanceof List<?> credentials) {
            for (Object item : credentials) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> credential = (Map<String, Object>) raw;
                String credentialId = toString(credential.get("id"));
                if (credentialId == null) continue;
                String key = "CREDENTIAL:" + credentialId;
                result.put(key, stricter(result.get(key), readLimit(credential)));
            }
        }
        return Map.copyOf(result);
    }

    private static CapacityStore.ScopeLimit readLimit(Map<String, Object> row) {
        return new CapacityStore.ScopeLimit(
                toLongOrNull(row.get("rpm_limit")),
                toLongOrNull(row.get("tpm_limit")),
                toIntOrNull(row.get("concurrent_limit")));
    }

    private static CapacityStore.ScopeLimit stricter(CapacityStore.ScopeLimit left,
                                                      CapacityStore.ScopeLimit right) {
        if (left == null) return right;
        return new CapacityStore.ScopeLimit(
                min(left.rpmLimit(), right.rpmLimit()),
                min(left.tpmLimit(), right.tpmLimit()),
                min(left.concurrentLimit(), right.concurrentLimit()));
    }

    private static <T extends Number & Comparable<T>> T min(T left, T right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.compareTo(right) <= 0 ? left : right;
    }

    private Optional<RawSnapshot> loadLatestActive(Connection connection) throws SQLException {
        String sql = "SELECT snapshot_no, content FROM " + qualify(connection, "config_snapshot")
                + " WHERE status = 'ACTIVE' ORDER BY snapshot_no DESC LIMIT 1";
        try (var statement = connection.prepareStatement(sql);
             var rs = statement.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new RawSnapshot(rs.getLong("snapshot_no"), rs.getString("content")));
        }
    }

    private Map<String, Object> parseContent(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode tree = mapper.readTree(json);
            if (tree.isTextual()) {
                tree = mapper.readTree(tree.asText());
            }
            return mapper.convertValue(tree, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("config_snapshot.content 反序列化失败", e);
        }
    }

    private List<AliasView> parseAliases(Map<String, Object> content) {
        Object aliasesObj = content.get("model_aliases");
        Object candidatesObj = content.get("route_candidates");
        Object modelsObj = content.get("provider_models");
        Object providersObj = content.get("providers");
        if (!(aliasesObj instanceof List<?> aliasList)) {
            return List.of();
        }
        Map<String, Map<String, Object>> providersById = indexBy(providersObj);
        Map<String, List<CandidateView>> candidatesByAlias = new LinkedHashMap<>();
        if (candidatesObj instanceof List<?> candList) {
            for (Object item : candList) {
                if (!(item instanceof Map<?, ?> rowMap)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) rowMap;
                CandidateView cv = parseCandidateView(row, modelsObj, providersById);
                if (cv == null) {
                    continue;
                }
                String aliasId = toString(row.get("alias_id"));
                candidatesByAlias.computeIfAbsent(aliasId, k -> new ArrayList<>()).add(cv);
            }
        }
        List<AliasView> result = new ArrayList<>();
        for (Object item : aliasList) {
            if (!(item instanceof Map<?, ?> rowMap)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) rowMap;
            String aliasId = toString(row.get("id"));
            String aliasName = toString(row.get("alias"));
            String displayName = toString(row.get("display_name"));
            boolean enabled = Boolean.TRUE.equals(toBoolOrNull(row.get("enabled")));
            List<CandidateView> candidates = candidatesByAlias.getOrDefault(aliasId, List.of());
            result.add(new AliasView(aliasId, aliasName, displayName, enabled, candidates));
        }
        return List.copyOf(result);
    }

    private static Map<String, Map<String, Object>> indexBy(Object rowsObj) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        if (rowsObj instanceof List<?> rows) {
            for (Object item : rows) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    String id = toString(typed.get("id"));
                    if (id != null) {
                        index.put(id, typed);
                    }
                }
            }
        }
        return index;
    }

    private CandidateView parseCandidateView(Map<String, Object> candidate, Object modelsObj,
                                             Map<String, Map<String, Object>> providersById) {
        if (!(modelsObj instanceof List<?>)) {
            return null;
        }
        String providerModelId = toString(candidate.get("provider_model_id"));
        Map<String, Object> model = findModel((List<?>) modelsObj, providerModelId);
        if (model == null) {
            return null;
        }
        String providerId = toString(model.get("provider_id"));
        Map<String, Object> provider = providersById.get(providerId);
        String providerType = provider == null ? null : toString(provider.get("type"));
        if (provider == null) {
            return null;
        }
        int connectTimeout = toIntOrZero(provider.get("connect_timeout_ms"));
        int readTimeout = toIntOrZero(provider.get("read_timeout_ms"));
        Map<String, String> defaultHeaders = readDefaultHeaders(provider.get("default_headers"));
        return new CandidateView(
                toString(candidate.get("id")),
                providerId,
                providerType,
                providerModelId,
                toString(model.get("model_id")),
                toString(candidate.get("credential_pool_id")),
                toLong(candidate.get("priority")),
                toInt(candidate.get("weight")),
                Boolean.TRUE.equals(toBoolOrNull(candidate.get("enabled"))),
                toString(model.get("tokenizer_family")),
                toLongOrNull(model.get("context_window")),
                toLongOrNull(model.get("max_output_tokens")),
                toBoolOrNull(model.get("support_stream")),
                toBoolOrNull(model.get("support_system_message")),
                toBoolOrNull(model.get("support_temperature")),
                toBoolOrNull(model.get("support_top_p")),
                toBoolOrNull(model.get("support_stop")),
                toBigDecimalOrZero(model.get("temperature_min")),
                toBigDecimalOrZero(model.get("temperature_max")),
                toBigDecimalOrZero(model.get("top_p_min")),
                toBigDecimalOrZero(model.get("top_p_max")),
                toIntOrNull(model.get("max_stop_sequences")),
                toBigDecimalOrZero(model.get("default_temperature")),
                toBigDecimalOrZero(model.get("default_top_p")),
                toLongOrNull(model.get("default_max_tokens")),
                toString(model.get("input_price")),
                toString(model.get("output_price")),
                toIntOrZero(model.get("price_unit")),
                toString(model.get("currency")),
                toString(provider.get("base_url")),
                toString(provider.get("proxy_url")),
                connectTimeout > 0 ? connectTimeout : 3000,
                readTimeout > 0 ? readTimeout : 120000,
                defaultHeaders
        );
    }

    /** default_headers 存储为 JSON 对象（string→string），失败或非对象时返回空表。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> readDefaultHeaders(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    headers.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
            return headers;
        }
        return Map.of();
    }

    private static Map<String, Object> findModel(List<?> modelList, String id) {
        for (Object item : modelList) {
            if (item instanceof Map<?, ?> map) {
                if (id != null && id.equals(toString(map.get("id")))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    return typed;
                }
            }
        }
        return null;
    }

    private static String toString(Object value) {
        if (value == null) return null;
        if (value instanceof UUID uuid) return uuid.toString();
        return value.toString();
    }

    private static UUID toUuidOrNull(Object value) {
        String text = toString(value);
        if (text == null || text.isBlank()) return null;
        try { return UUID.fromString(text); } catch (IllegalArgumentException ignored) { return null; }
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static double decimalOrDefault(Object value, double fallback) {
        BigDecimal decimal = toBigDecimalOrZero(value);
        return decimal.signum() > 0 ? decimal.doubleValue() : fallback;
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { return 0L; }
        }
        return 0L;
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private static Integer toIntOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static Long toLongOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static int toIntOrZero(Object value) {
        Integer v = toIntOrNull(value);
        return v == null ? 0 : v;
    }

    private static Boolean toBoolOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s);
        if (value instanceof Number n) return n.intValue() != 0;
        return null;
    }

    private static BigDecimal toBigDecimalOrZero(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        if (value instanceof String s && !s.isBlank()) {
            try { return new BigDecimal(s); } catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
        }
        return BigDecimal.ZERO;
    }

    private record CachedSnapshot(long maxSnapshotNo, ActiveSnapshot snapshot) {
    }

    private record RawSnapshot(long snapshotNo, String contentJson) {
    }
}
