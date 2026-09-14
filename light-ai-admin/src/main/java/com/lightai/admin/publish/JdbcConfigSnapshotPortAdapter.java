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

    /**
     * Resolve the V2 application mapping directly for business requests.
     * Application mappings are versioned independently from the legacy global
     * publish snapshot, so an application can route a manually entered model
     * without creating a global virtual_model row.
     */
    @Override
    public ActiveSnapshot active(com.lightai.runtime.ports.AccessTokenPort.Principal principal) {
        if (principal == null || principal.applicationId() == null || principal.applicationId().isBlank()) {
            return active();
        }
        try {
            return readApplicationSnapshot(UUID.fromString(principal.applicationId()));
        } catch (RuntimeException e) {
            return new ActiveSnapshot(0L, List.of());
        }
    }

    private ActiveSnapshot readApplicationSnapshot(UUID applicationId) {
        if (connectionSupplier == null) return new ActiveSnapshot(0L, List.of());
        Map<String, List<CandidateView>> candidates = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        long revision = 0L;
        try (Connection connection = connectionSupplier.get()) {
            String sql = "SELECT r.revision, m.id AS mapping_id, m.public_model_name, "
                    + "t.id AS target_id, t.channel_id, t.upstream_model_id, t.upstream_model_name, "
                    + "t.priority, t.weight, c.base_url, c.proxy_url, c.connect_timeout_ms, "
                    + "c.read_timeout_ms, c.default_headers, p.type AS provider_type, "
                    + "u.model_id, u.tokenizer_family, u.context_window, u.max_output_tokens, "
                    + "u.support_stream, u.support_system_message, u.support_temperature, "
                    + "u.support_top_p, u.support_stop, u.temperature_min, u.temperature_max, "
                    + "u.top_p_min, u.top_p_max, u.max_stop_sequences, u.default_temperature, "
                    + "u.default_top_p, u.default_max_tokens, u.input_price, u.output_price, "
                    + "u.price_unit, u.currency "
                    + "FROM " + qualify(connection, "application_config_revision") + " r "
                    + "JOIN " + qualify(connection, "application_model_mapping")
                    + " m ON m.revision_id = r.id AND m.status = 'ACTIVE' "
                    + "JOIN " + qualify(connection, "application_model_target")
                    + " t ON t.mapping_id = m.id AND t.status = 'ACTIVE' "
                    + "JOIN " + qualify(connection, "channel")
                    + " c ON c.id = t.channel_id AND c.status = 'ACTIVE' AND c.deleted_at IS NULL "
                    + "LEFT JOIN " + qualify(connection, "provider") + " p ON p.id = c.provider_id "
                    + "LEFT JOIN " + qualify(connection, "upstream_model")
                    + " u ON u.id = t.upstream_model_id AND u.status = 'ACTIVE' AND u.deleted_at IS NULL "
                    + "WHERE r.application_id = ? AND r.status = 'ACTIVE' "
                    + "ORDER BY m.public_model_name, t.priority, t.weight DESC, t.id";
            try (var statement = connection.prepareStatement(sql)) {
                dialect(connection).bindUuid(statement, 1, applicationId);
                try (var rs = statement.executeQuery()) {
                    while (rs.next()) {
                        revision = Math.max(revision, rs.getLong("revision"));
                        String mappingId = toString(rs.getObject("mapping_id"));
                        String publicName = rs.getString("public_model_name");
                        String providerType = rs.getString("provider_type");
                        String channelId = toString(rs.getObject("channel_id"));
                        String targetId = toString(rs.getObject("target_id"));
                        String modelPk = toString(rs.getObject("upstream_model_id"));
                        String modelId = rs.getString("model_id");
                        if (modelId == null || modelId.isBlank()) modelId = rs.getString("upstream_model_name");
                        if (mappingId == null || publicName == null || providerType == null
                                || channelId == null || targetId == null || modelId == null) continue;
                        if (modelPk == null || modelPk.isBlank()) modelPk = targetId;
                        CandidateView candidate = new CandidateView(
                                targetId, channelId, providerType, modelPk, modelId,
                                rs.getLong("priority"), rs.getInt("weight"), true,
                                rs.getString("tokenizer_family"),
                                applicationLongOrDefault(rs.getObject("context_window"), 128000L),
                                applicationLongOrDefault(rs.getObject("max_output_tokens"), 16384L),
                                applicationBoolOrDefault(rs.getObject("support_stream"), true),
                                applicationBoolOrDefault(rs.getObject("support_system_message"), true),
                                applicationBoolOrDefault(rs.getObject("support_temperature"), true),
                                applicationBoolOrDefault(rs.getObject("support_top_p"), true),
                                applicationBoolOrDefault(rs.getObject("support_stop"), true),
                                toBigDecimalOrZero(rs.getObject("temperature_min")),
                                toBigDecimalOrZero(rs.getObject("temperature_max")),
                                toBigDecimalOrZero(rs.getObject("top_p_min")),
                                toBigDecimalOrZero(rs.getObject("top_p_max")),
                                toIntOrNull(rs.getObject("max_stop_sequences")),
                                toBigDecimalOrZero(rs.getObject("default_temperature")),
                                toBigDecimalOrZero(rs.getObject("default_top_p")),
                                toLongOrNull(rs.getObject("default_max_tokens")),
                                applicationStringOrDefault(rs.getObject("input_price"), "0"),
                                applicationStringOrDefault(rs.getObject("output_price"), "0"),
                                applicationIntOrDefault(rs.getObject("price_unit"), 1000000),
                                applicationStringOrDefault(rs.getObject("currency"), "USD"),
                                rs.getString("base_url"), rs.getString("proxy_url"),
                                applicationIntOrDefault(rs.getObject("connect_timeout_ms"), 3000),
                                applicationIntOrDefault(rs.getObject("read_timeout_ms"), 120000),
                                readDefaultHeaders(rs.getString("default_headers")));
                        names.putIfAbsent(mappingId, publicName);
                        candidates.computeIfAbsent(mappingId, ignored -> new ArrayList<>()).add(candidate);
                    }
                }
            }
        } catch (Exception e) {
            return new ActiveSnapshot(0L, List.of());
        }
        List<AliasView> aliases = new ArrayList<>();
        for (Map.Entry<String, List<CandidateView>> entry : candidates.entrySet()) {
            String publicName = names.get(entry.getKey());
            if (entry.getValue().isEmpty()) continue;
            aliases.add(new AliasView(entry.getKey(), publicName, publicName, true, entry.getValue()));
        }
        return new ActiveSnapshot(revision, aliases);
    }

    private static String applicationStringOrDefault(Object value, String fallback) {
        String text = toString(value);
        return text == null || text.isBlank() ? fallback : text;
    }

    private static Boolean applicationBoolOrDefault(Object value, boolean fallback) {
        Boolean parsed = toBoolOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    private static long applicationLongOrDefault(Object value, long fallback) {
        Long parsed = toLongOrNull(value);
        return parsed == null || parsed <= 0 ? fallback : parsed;
    }

    private static int applicationIntOrDefault(Object value, int fallback) {
        Integer parsed = toIntOrNull(value);
        return parsed == null || parsed <= 0 ? fallback : parsed;
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
                String channelCredentialId = toString(credential.get("id"));
                if (channelCredentialId == null) continue;
                String key = "CREDENTIAL:" + channelCredentialId;
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

    /* package */ List<AliasView> parseAliases(Map<String, Object> content) {
        Object aliasesObj = content.get("model_aliases");
        Object candidatesObj = content.get("route_candidates");
        Object modelsObj = content.get("upstream_models");
        // V2 snapshots store provider connection data under channels. Keep the
        // legacy providers key as a fallback so older snapshots remain readable.
        Object providersObj = content.containsKey("channels")
                ? content.get("channels") : content.get("providers");
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
        String upstreamModelId = toString(candidate.get("upstream_model_id"));
        Map<String, Object> model = findModel((List<?>) modelsObj, upstreamModelId);
        if (model == null) {
            return null;
        }
        String channelId = toString(model.get("channel_id"));
        Map<String, Object> provider = providersById.get(channelId);
        String providerType = provider == null ? null : toString(provider.get("provider_type"));
        if (providerType == null && provider != null) {
            providerType = toString(provider.get("type"));
        }
        if (provider == null) {
            return null;
        }
        int connectTimeout = toIntOrZero(provider.get("connect_timeout_ms"));
        int readTimeout = toIntOrZero(provider.get("read_timeout_ms"));
        Map<String, String> defaultHeaders = readDefaultHeaders(provider.get("default_headers"));
        return new CandidateView(
                toString(candidate.get("id")),
                channelId,
                providerType,
                upstreamModelId,
                toString(model.get("model_id")),
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
        if (raw instanceof String json && !json.isBlank()) {
            try {
                raw = ProtocolJson.protocol().readValue(json, new TypeReference<Map<String, String>>() { });
            } catch (Exception ignored) {
                return Map.of();
            }
        }
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
