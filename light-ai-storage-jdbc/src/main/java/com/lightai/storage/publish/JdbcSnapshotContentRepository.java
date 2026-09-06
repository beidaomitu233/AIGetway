package com.lightai.storage.publish;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ConfigSnapshot.content 装配与恢复（BE-038/BE-040/BE-041）。
 * 白名单字段来自各配置表（DATABASE_PLAN snapshot.content 口径）：
 * 排除 created_at/updated_at/deleted_at、全部秘密列与完整 secret_ref、运行状态列。
 * 序列化采用固定键序 + 数组按 id 排序，checksum 对规范化 JSON 字节计算。
 */
public final class JdbcSnapshotContentRepository implements SnapshotContentRepository {

    private final String schemaName;

    public JdbcSnapshotContentRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcSnapshotContentRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** 实体类型 → (存储表, JSON 键, 白名单列)。顺序即快照键序。 */
    private static final List<EntityColumns> ENTITIES = List.of(
            new EntityColumns("provider", "providers",
                    "id, name, type, base_url, proxy_url, connect_timeout_ms, read_timeout_ms, "
                            + "default_headers, enabled, version",
                    java.util.Set.of("default_headers"),
                    java.util.Set.of()),
            new EntityColumns("credential_pool", "credential_pools",
                    "id, provider_id, name, selection_strategy, enabled, version",
                    java.util.Set.of(),
                    java.util.Set.of("provider_id")),
            new EntityColumns("credential", "credentials",
                    "id, pool_id, name, secret_source, weight, rpm_limit, tpm_limit, concurrent_limit, "
                            + "enabled, version",
                    java.util.Set.of(),
                    java.util.Set.of("pool_id")),
            new EntityColumns("provider_model", "provider_models",
                    "id, provider_id, model_id, display_name, model_type, tokenizer_family, context_window, "
                            + "max_output_tokens, support_stream, support_system_message, support_temperature, "
                            + "support_top_p, support_stop, temperature_min, temperature_max, top_p_min, top_p_max, "
                            + "max_stop_sequences, max_stop_length, default_temperature, default_top_p, "
                            + "default_max_tokens, default_stop, input_price, output_price, price_unit, currency, "
                            + "enabled, import_source, import_adapter_version, version",
                    java.util.Set.of(),
                    java.util.Set.of("provider_id")),
            new EntityColumns("model_alias", "model_aliases",
                    "id, alias, display_name, description, route_strategy, enabled, version",
                    java.util.Set.of(),
                    java.util.Set.of()),
            new EntityColumns("route_candidate", "route_candidates",
                    "id, alias_id, provider_model_id, credential_pool_id, priority, weight, enabled, version",
                    java.util.Set.of(),
                    java.util.Set.of("alias_id", "provider_model_id", "credential_pool_id")),
            new EntityColumns("limit_policy", "limit_policies",
                    "id, name, scope_type, scope_id, rpm_limit, tpm_limit, concurrent_limit, "
                            + "overflow_strategy, queue_timeout_ms, queue_max_size, enabled, version",
                    java.util.Set.of(),
                    java.util.Set.of("scope_id")),
            new EntityColumns("reliability_policy", "reliability_policies",
                    "id, name, alias_id, connect_timeout_ms, first_token_timeout_ms, total_timeout_ms, "
                            + "max_retries, max_credential_failovers, initial_backoff_ms, backoff_multiplier, "
                            + "jitter_percent, respect_retry_after, max_retry_after_ms, fallback_enabled, "
                            + "max_fallbacks, circuit_window_seconds, circuit_min_requests, circuit_failure_rate, "
                            + "circuit_open_seconds, circuit_half_open_probes, circuit_half_open_successes, "
                            + "enabled, version",
                    java.util.Set.of(),
                    java.util.Set.of("alias_id")));

    /** 当前全部活行配置的规范化快照树（固定键序，数组按 id asc）。 */
    public Map<String, Object> assemble(Connection connection, String timezone) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("schema_version", 1);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EntityColumns entity : ENTITIES) {
            List<Map<String, Object>> rows = readRows(connection, entity);
            counts.put(entity.jsonKey(), (long) rows.size());
            content.put(entity.jsonKey(), rows);
        }
        Map<String, Object> runtimeConfig = new LinkedHashMap<>();
        runtimeConfig.put("timezone", timezone);
        content.put("runtime_config", runtimeConfig);
        content.put("content_summary", counts);
        return content;
    }

    /** 数量安全摘要（config_snapshot.content_summary）。 */
    public Map<String, Long> summarize(Map<String, Object> content) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EntityColumns entity : ENTITIES) {
            Object rows = content.get(entity.jsonKey());
            counts.put(entity.jsonKey(), rows instanceof List<?> list ? (long) list.size() : 0L);
        }
        return counts;
    }

    /** 规范化 JSON 字符串（固定键序，ProtocolJson BigDecimal/时间口径），checksum 输入。 */
    public String canonicalJson(Map<String, Object> content) {
        try {
            return com.lightai.client.json.ProtocolJson.protocol().writeValueAsString(content);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("快照内容规范化序列化失败", e);
        }
    }

    /** 恢复快照行：upsert 白名单字段并重新生成 version；返回恢复的 (类型, id) 集合。 */
    public List<RestoredEntity> restore(Connection connection, Map<String, Object> content) {
        List<RestoredEntity> restored = new ArrayList<>();
        for (EntityColumns entity : ENTITIES) {
            Object rows = content.get(entity.jsonKey());
            if (!(rows instanceof List<?> list)) {
                continue;
            }
            for (Object rowObject : list) {
                if (rowObject instanceof Map<?, ?> row) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) row;
                    upsertRow(connection, entity, typed);
                    restored.add(new RestoredEntity(entity.entityType(),
                            String.valueOf(typed.get("id"))));
                }
            }
        }
        return restored;
    }

    /** 全量活行读取（发布前草稿对比与快照内容生成都使用同一白名单）。 */
    public List<Map<String, Object>> readRows(Connection connection, EntityColumns entity) {
        String sql = "SELECT " + entity.columns() + " FROM " + schemaName + "." + entity.table()
                + " WHERE deleted_at IS NULL ORDER BY id";
        String[] columns = entity.columns().split(", ");
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String column : columns) {
                    row.put(column, readValue(rs, column, entity));
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("快照行读取失败：" + entity.table() + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    private void upsertRow(Connection connection, EntityColumns entity, Map<String, Object> row) {
        String[] columns = entity.columns().split(", ");
        String updates = java.util.Arrays.stream(entity.columns().split(", "))
                .filter(column -> !column.equals("id"))
                .map(column -> column + " = EXCLUDED." + column)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        String sql = "INSERT INTO " + schemaName + "." + entity.table()
                + " (" + entity.columns() + ", created_at, updated_at, deleted_at) "
                + "VALUES (" + placeholders(entity.columns(), entity) + ", now(), now(), NULL) "
                + "ON CONFLICT (id) DO UPDATE SET " + updates
                + ", deleted_at = NULL, updated_at = now()";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String column : columns) {
                index = bindValue(statement, index, column, row.get(column), entity);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("快照行恢复失败：" + entity.table() + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    private static String placeholders(String columns, EntityColumns entity) {
        String[] parts = columns.split(", ");
        StringBuilder markers = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                markers.append(", ");
            }
            markers.append(entity.jsonbColumns().contains(parts[i]) ? "?::jsonb" : "?");
        }
        return markers.toString();
    }

    private Object readValue(ResultSet rs, String column, EntityColumns entity) throws SQLException {
        if (entity.jsonbColumns().contains(column)) {
            String raw = rs.getString(column);
            if (raw == null) {
                return null;
            }
            try {
                return com.lightai.client.json.ProtocolJson.protocol().readValue(raw, Map.class);
            } catch (Exception e) {
                throw new IllegalStateException("快照 jsonb 反序列化失败：" + column, e);
            }
        }
        Object value = rs.getObject(column);
        return normalize(value);
    }

    private static Object normalize(Object value) {
        if (value instanceof java.util.UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.time.OffsetDateTime dateTime) {
            return dateTime.toString();
        }
        return value;
    }

    private int bindValue(PreparedStatement statement, int index, String column, Object value,
                          EntityColumns entity) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
            return index + 1;
        }
        if (entity.jsonbColumns().contains(column)) {
            try {
                statement.setString(index, com.lightai.client.json.ProtocolJson.protocol()
                        .writeValueAsString(value));
            } catch (Exception e) {
                throw new IllegalStateException("快照 jsonb 序列化失败：" + column, e);
            }
            return index + 1;
        }
        if (entity.uuidColumns().contains(column)) {
            statement.setObject(index, java.util.UUID.fromString(String.valueOf(value)));
            return index + 1;
        }
        if (value instanceof String text) {
            statement.setString(index, text);
        } else if (value instanceof Long longValue) {
            statement.setLong(index, longValue);
        } else if (value instanceof Integer intValue) {
            statement.setInt(index, intValue);
        } else if (value instanceof java.math.BigDecimal decimal) {
            statement.setBigDecimal(index, decimal);
        } else if (value instanceof Boolean bool) {
            statement.setBoolean(index, bool);
        } else {
            statement.setObject(index, value);
        }
        return index + 1;
    }

    /** 撤销 CREATE：草稿新建对象软删除并重新生成 version（BE-038）。 */
    public int deleteDraftObject(Connection connection, String entityType, String entityId) {
        EntityColumns entity = requireEntity(entityType);
        String sql = "UPDATE " + schemaName + "." + entity.table()
                + " SET deleted_at = now(), version = version + 1, updated_at = now() "
                + "WHERE id = ?::uuid AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("草稿对象删除失败：" + entity.table() + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    /** 撤销 DELETE：仅清除删除标记并重新生成 version（BE-038，4.5.1.5）。 */
    public int restoreUndelete(Connection connection, String entityType, String entityId) {
        EntityColumns entity = requireEntity(entityType);
        String sql = "UPDATE " + schemaName + "." + entity.table()
                + " SET deleted_at = NULL, version = version + 1, updated_at = now() "
                + "WHERE id = ?::uuid";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("草稿对象恢复失败：" + entity.table() + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public String contentKeyOf(String entityType) {
        return requireEntity(entityType).jsonKey();
    }

    private EntityColumns requireEntity(String entityType) {
        return ENTITIES.stream()
                .filter(candidate -> candidate.entityType().equals(entityType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未知配置实体类型：" + entityType));
    }

    /** 实体白名单定义：select 与 upsert 共用同一列序。 */
    public record EntityColumns(
            String entityType,
            String jsonKey,
            String columns,
            java.util.Set<String> jsonbColumns,
            java.util.Set<String> uuidColumns) {

        String table() {
            return entityType;
        }
    }

}
