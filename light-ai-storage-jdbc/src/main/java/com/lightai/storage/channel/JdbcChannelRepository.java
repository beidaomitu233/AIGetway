package com.lightai.storage.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lightai.client.json.ProtocolJson;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * channel JDBC 仓储（DATABASE_PLAN §1）。
 * V2：channel 承接连接语义，provider_id 指向协议类型目录（provider），
 * provider_type 由 provider.type 联表读出；启停以 status(ACTIVE/DISABLED) 表达。
 * 支持 PostgreSQL 与 MySQL 5.7 / 8.0 双方言自适应。
 */
public class JdbcChannelRepository extends AbstractJdbcRepository {

    /** 联表读列：c 为 channel 别名，p 为 provider 目录别名。 */
    private static final String SELECT_COLUMNS =
            "c.id, c.provider_id, p.type AS provider_type, c.name, c.base_url, c.proxy_url, "
                    + "c.connect_timeout_ms, c.read_timeout_ms, c.stream_idle_timeout_ms, c.default_headers, "
                    + "c.priority, c.weight, c.status, c.health, c.version, c.created_at, c.updated_at";

    private static final String INSERT_COLUMNS =
            "id, provider_id, name, base_url, proxy_url, connect_timeout_ms, read_timeout_ms, "
                    + "stream_idle_timeout_ms, default_headers, priority, weight, status, health, version, "
                    + "created_at, updated_at";

    private static final String FROM_TAIL = " c LEFT JOIN provider p ON p.id = c.provider_id";

    public JdbcChannelRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcChannelRepository(String schemaName) {
        super(schemaName);
    }

    /** 渠道主表（含协议类型目录连接）的 schema 限定 FROM 片段。 */
    private String from(Connection connection) {
        return qualify(connection, "channel") + FROM_TAIL;
    }

    public JdbcChannelRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** 由协议类型解析目录主键（provider.type 唯一）。 */
    public Optional<UUID> resolveProviderId(Connection connection, String providerType) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT id FROM " + qualify(connection, "provider") + " WHERE type = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerType);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(d.readUuid(rs, "id")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("协议类型解析失败", e);
        }
    }

    public void insert(Connection connection, ChannelRecord record) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "channel") + " (" + INSERT_COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + d.jsonPlaceholder() + ", ?, ?, ?, ?, ?, "
                + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, record.id());
            d.bindUuid(statement, 2, record.providerId());
            statement.setString(3, record.name());
            statement.setString(4, record.baseUrl());
            statement.setString(5, record.proxyUrl());
            statement.setInt(6, record.connectTimeoutMs());
            statement.setInt(7, record.readTimeoutMs());
            statement.setInt(8, record.streamIdleTimeoutMs());
            statement.setString(9, toJson(record.defaultHeaders()));
            statement.setInt(10, record.priority());
            statement.setInt(11, record.weight());
            statement.setString(12, record.status());
            statement.setString(13, record.health());
            statement.setLong(14, record.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("渠道写入失败", e);
        }
    }

    public Optional<ChannelRecord> findLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + from(connection)
                + " WHERE c.id = ? AND c.deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("渠道读取失败", e);
        }
    }

    public Optional<ChannelRecord> lockLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + from(connection)
                + " WHERE c.id = ? AND c.deleted_at IS NULL " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("渠道锁定失败", e);
        }
    }

    public boolean existsByLiveName(Connection connection, String name) {
        String sql = "SELECT 1 FROM " + qualify(connection, "channel") + " WHERE name = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("渠道名称检查失败", e);
        }
    }

    /** 唯一约束兜底检查：排除自身（编辑未改名场景）。 */
    public boolean existsByLiveNameExcept(Connection connection, String name, UUID exceptId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "channel") + " WHERE name = ? AND id <> ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            d.bindUuid(statement, 2, exceptId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("渠道名称检查失败", e);
        }
    }

    /** 更新可编辑字段并递增 version；provider_id 不可变；version 校验由草稿写事务先行完成。 */
    public ChannelRecord update(Connection connection, ChannelRecord record) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "channel") + " SET "
                + "name = ?, base_url = ?, proxy_url = ?, connect_timeout_ms = ?, read_timeout_ms = ?, "
                + "stream_idle_timeout_ms = ?, default_headers = " + d.jsonPlaceholder() + ", "
                + "priority = ?, weight = ?, status = ?, "
                + "version = version + 1, updated_at = " + d.nowFunction() + " "
                + "WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.name());
            statement.setString(2, record.baseUrl());
            statement.setString(3, record.proxyUrl());
            statement.setInt(4, record.connectTimeoutMs());
            statement.setInt(5, record.readTimeoutMs());
            statement.setInt(6, record.streamIdleTimeoutMs());
            statement.setString(7, toJson(record.defaultHeaders()));
            statement.setInt(8, record.priority());
            statement.setInt(9, record.weight());
            statement.setString(10, record.status());
            d.bindUuid(statement, 11, record.id());
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new IllegalStateException("渠道更新未命中活行");
            }
            return findLiveById(connection, record.id()).orElseThrow(() -> new IllegalStateException("渠道更新未命中活行"));
        } catch (SQLException e) {
            throw translate("渠道更新失败", e);
        }
    }

    /** 软删除；引用检查由服务层先行完成。 */
    public void markDeleted(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "channel") + " SET deleted_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("渠道删除失败", e);
        }
    }

    /** 启停：status 在 ACTIVE/DISABLED 间切换，version+1；发布后才影响新调用。 */
    public ChannelRecord setEnabled(Connection connection, UUID id, boolean enabled) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "channel")
                + " SET status = ?, version = version + 1, updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, enabled ? ChannelRecord.STATUS_ACTIVE : ChannelRecord.STATUS_DISABLED);
            d.bindUuid(statement, 2, id);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new IllegalStateException("渠道启停未命中活行");
            }
            return findLiveById(connection, id).orElseThrow(() -> new IllegalStateException("渠道启停未命中活行"));
        } catch (SQLException e) {
            throw translate("渠道启停失败", e);
        }
    }

    public List<ChannelRecord> list(Connection connection, ChannelFilter filter,
                                    String sortExpression, int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS).append(" FROM ")
                .append(from(connection)).append(" WHERE c.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, filter, params, connection, d);
        sql.append(" ORDER BY ").append(sortExpression).append(", c.id ASC ").append(d.limitOffsetClause(limit, offset));
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChannelRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs, d));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("渠道列表查询失败", e);
        }
    }

    public long count(Connection connection, ChannelFilter filter) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(from(connection))
                .append(" WHERE c.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, filter, params, connection, d);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("渠道计数失败", e);
        }
    }

    /** 列表筛选：keyword 命中名称，provider_type/status 精确匹配；
     * health/draft_changed 为运行与差异关联过滤。 */
    public record ChannelFilter(String keyword, String providerType, String status,
                                String health, Boolean draftChanged) {

        public ChannelFilter(String keyword, String providerType, String status) {
            this(keyword, providerType, status, null, null);
        }
    }

    private void appendFilter(StringBuilder sql, ChannelFilter filter, List<Object> params, Connection connection, DatabaseDialect d) {
        if (filter == null) {
            return;
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            sql.append(" AND ").append(d.ilikeClause("c.name"));
            params.add("%" + filter.keyword().strip() + "%");
        }
        if (filter.providerType() != null && !filter.providerType().isBlank()) {
            sql.append(" AND p.type = ?");
            params.add(filter.providerType().strip());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND c.status = ?");
            params.add(filter.status().strip());
        }
        if (filter.health() != null && !filter.health().isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(qualify(connection, "object_runtime_state")).append(" s")
                    .append(" WHERE s.entity_type = 'CHANNEL' AND s.entity_id = c.id")
                    .append(" AND s.connection_status = ?)");
            params.add(filter.health().strip());
        }
        if (filter.draftChanged() != null) {
            sql.append(" AND ").append(filter.draftChanged() ? "" : "NOT ").append(
                    "EXISTS (SELECT 1 FROM ").append(qualify(connection, "draft_change")).append(" dc")
                    .append(" WHERE dc.entity_type = 'CHANNEL' AND dc.entity_id = c.id)");
        }
    }

    private ChannelRecord mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new ChannelRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "provider_id"),
                rs.getString("provider_type"),
                rs.getString("name"),
                rs.getString("base_url"),
                rs.getString("proxy_url"),
                rs.getInt("connect_timeout_ms"),
                rs.getInt("read_timeout_ms"),
                rs.getInt("stream_idle_timeout_ms"),
                fromJson(rs.getString("default_headers")),
                rs.getInt("priority"),
                rs.getInt("weight"),
                rs.getString("status"),
                rs.getString("health"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
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
}
