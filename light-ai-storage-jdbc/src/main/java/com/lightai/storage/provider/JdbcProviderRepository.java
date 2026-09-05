package com.lightai.storage.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lightai.client.json.ProtocolJson;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * provider JDBC 仓储（DATABASE_PLAN §1）。
 * 活行语义 deleted_at IS NULL；软删除仅置 deleted_at；排序表达由服务层白名单生成。
 */
public class JdbcProviderRepository {

    private static final String COLUMNS =
            "id, name, type, base_url, proxy_url, connect_timeout_ms, read_timeout_ms, "
                    + "default_headers, enabled, version, created_at, updated_at";

    private final String schemaName;

    public JdbcProviderRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcProviderRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, ProviderRecord record) {
        String sql = "INSERT INTO %s.provider (%s) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now(), now())"
                .formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, record.id());
            statement.setString(2, record.name());
            statement.setString(3, record.type());
            statement.setString(4, record.baseUrl());
            statement.setString(5, record.proxyUrl());
            statement.setInt(6, record.connectTimeoutMs());
            statement.setInt(7, record.readTimeoutMs());
            statement.setString(8, toJson(record.defaultHeaders()));
            statement.setBoolean(9, record.enabled());
            statement.setLong(10, record.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("Provider写入失败", e);
        }
    }

    public Optional<ProviderRecord> findLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("Provider读取失败", e);
        }
    }

    public Optional<ProviderRecord> lockLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE id = ? AND deleted_at IS NULL FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("Provider锁定失败", e);
        }
    }

    public boolean existsByLiveName(Connection connection, String name) {
        String sql = "SELECT 1 FROM " + qualified() + " WHERE name = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("Provider名称检查失败", e);
        }
    }

    /** 唯一约束兜底检查：排除自身（编辑未改名场景）。 */
    public boolean existsByLiveNameExcept(Connection connection, String name, UUID exceptId) {
        String sql = "SELECT 1 FROM " + qualified() + " WHERE name = ? AND id <> ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setObject(2, exceptId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("Provider名称检查失败", e);
        }
    }

    /** 更新可编辑字段并递增 version；version 校验由草稿写事务先行完成。 */
    public ProviderRecord update(Connection connection, ProviderRecord record) {
        String sql = """
                UPDATE %s.provider
                   SET name = ?, type = ?, base_url = ?, proxy_url = ?, connect_timeout_ms = ?,
                       read_timeout_ms = ?, default_headers = ?::jsonb, enabled = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                RETURNING %s
                """.strip().formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.name());
            statement.setString(2, record.type());
            statement.setString(3, record.baseUrl());
            statement.setString(4, record.proxyUrl());
            statement.setInt(5, record.connectTimeoutMs());
            statement.setInt(6, record.readTimeoutMs());
            statement.setString(7, toJson(record.defaultHeaders()));
            statement.setBoolean(8, record.enabled());
            statement.setObject(9, record.id());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Provider更新未命中活行");
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw translate("Provider更新失败", e);
        }
    }

    /** 软删除；引用检查由服务层先行完成。 */
    public void markDeleted(Connection connection, UUID id) {
        String sql = "UPDATE %s.provider SET deleted_at = now(), updated_at = now() "
                + "WHERE id = ? AND deleted_at IS NULL".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("Provider删除失败", e);
        }
    }

    /** 启停：仅改 enabled，version+1；发布后才影响新调用。 */
    public ProviderRecord setEnabled(Connection connection, UUID id, boolean enabled) {
        String sql = """
                UPDATE %s.provider
                   SET enabled = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                RETURNING %s
                """.strip().formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, enabled);
            statement.setObject(2, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Provider启停未命中活行");
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw translate("Provider启停失败", e);
        }
    }

    public List<ProviderRecord> list(Connection connection, ProviderFilter filter,
                                     String sortExpression, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualified()).append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, filter, params);
        sql.append(" ORDER BY ").append(sortExpression).append(", id ASC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, params);
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<ProviderRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("Provider列表查询失败", e);
        }
    }

    public long count(Connection connection, ProviderFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualified())
                .append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, filter, params);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("Provider计数失败", e);
        }
    }

    /** 列表筛选：keyword 命中名称，type/enabled 精确匹配；
     * connection_status/draft_changed 为运行与差异关联过滤。 */
    public record ProviderFilter(String keyword, String type, Boolean enabled,
                                 String connectionStatus, Boolean draftChanged) {

        public ProviderFilter(String keyword, String type, Boolean enabled) {
            this(keyword, type, enabled, null, null);
        }
    }

    private void appendFilter(StringBuilder sql, ProviderFilter filter, List<Object> params) {
        if (filter == null) {
            return;
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            sql.append(" AND name ILIKE ?");
            params.add("%" + filter.keyword().strip() + "%");
        }
        if (filter.type() != null && !filter.type().isBlank()) {
            sql.append(" AND type = ?");
            params.add(filter.type().strip());
        }
        if (filter.enabled() != null) {
            sql.append(" AND enabled = ?");
            params.add(filter.enabled());
        }
        if (filter.connectionStatus() != null && !filter.connectionStatus().isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(schemaName)
                    .append(".object_runtime_state s")
                    .append(" WHERE s.entity_type = 'PROVIDER' AND s.entity_id = provider.id")
                    .append(" AND s.connection_status = ?)");
            params.add(filter.connectionStatus().strip());
        }
        if (filter.draftChanged() != null) {
            sql.append(" AND ").append(filter.draftChanged() ? "" : "NOT ").append(
                    "EXISTS (SELECT 1 FROM ").append(schemaName).append(".draft_change dc")
                    .append(" WHERE dc.entity_type = 'PROVIDER' AND dc.entity_id = provider.id)");
        }
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    private ProviderRecord mapRow(ResultSet rs) throws SQLException {
        return new ProviderRecord(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("type"),
                rs.getString("base_url"),
                rs.getString("proxy_url"),
                rs.getInt("connect_timeout_ms"),
                rs.getInt("read_timeout_ms"),
                fromJson(rs.getString("default_headers")),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String toJson(Map<String, String> headers) {
        try {
            return ProtocolJson.protocol().writeValueAsString(headers);
        } catch (Exception e) {
            throw new IllegalStateException("default_headers 序列化失败", e);
        }
    }

    private static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return ProtocolJson.protocol().readValue(json, new TypeReference<HashMap<String, String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("default_headers 解析失败", e);
        }
    }

    private String qualified() {
        return schemaName + ".provider";
    }

    private static IllegalStateException translate(String message, SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if ("23505".equals(state)) {
            return new IllegalStateException("UNIQUE_VIOLATION: " + message, e);
        }
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
