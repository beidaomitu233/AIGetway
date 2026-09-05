package com.lightai.storage.alias;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * model_alias JDBC 仓储（DATABASE_PLAN §6）。
 * alias 活行全局唯一；删除前引用检查由服务层完成。
 */
public class JdbcAliasRepository {

    private static final String COLUMNS =
            "id, alias, display_name, description, route_strategy, enabled, version, created_at, updated_at";

    protected final String schemaName;

    public JdbcAliasRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcAliasRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, AliasRecord record) {
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at"));
        String sql = "INSERT INTO %s.model_alias (%s, created_at, updated_at) VALUES (%s, now(), now())"
                .formatted(qualified(), insertColumns, placeholders(insertColumns));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, record.id());
            statement.setString(2, record.alias());
            statement.setString(3, record.displayName());
            statement.setString(4, record.description());
            statement.setString(5, record.routeStrategy());
            statement.setBoolean(6, record.enabled());
            statement.setLong(7, record.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("Alias写入失败", e);
        }
    }

    public Optional<AliasRecord> findLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("Alias读取失败", e);
        }
    }

    public Optional<AliasRecord> lockLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE id = ? AND deleted_at IS NULL FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("Alias锁定失败", e);
        }
    }

    public boolean existsByLiveAlias(Connection connection, String alias) {
        String sql = "SELECT 1 FROM " + qualified() + " WHERE alias = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, alias);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("Alias唯一性检查失败", e);
        }
    }

    public AliasRecord update(Connection connection, AliasRecord record) {
        String sql = """
                UPDATE %s.model_alias
                   SET display_name = ?, description = ?, enabled = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                RETURNING %s
                """.strip().formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.displayName());
            statement.setString(2, record.description());
            statement.setBoolean(3, record.enabled());
            statement.setObject(4, record.id());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Alias更新未命中活行");
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw translate("Alias更新失败", e);
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        String sql = "UPDATE %s.model_alias SET deleted_at = now(), updated_at = now() "
                + "WHERE id = ? AND deleted_at IS NULL".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("Alias删除失败", e);
        }
    }

    public List<AliasRecord> list(Connection connection, String keyword, Boolean enabled,
                                  String sortExpression, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualified()).append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (alias ILIKE ? OR display_name ILIKE ?)");
            params.add("%" + keyword.strip() + "%");
            params.add("%" + keyword.strip() + "%");
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        sql.append(" ORDER BY ").append(sortExpression).append(", id ASC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<AliasRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("Alias列表查询失败", e);
        }
    }

    public long count(Connection connection, String keyword, Boolean enabled) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualified())
                .append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (alias ILIKE ? OR display_name ILIKE ?)");
            params.add("%" + keyword.strip() + "%");
            params.add("%" + keyword.strip() + "%");
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("Alias计数失败", e);
        }
    }

    private AliasRecord mapRow(ResultSet rs) throws SQLException {
        return new AliasRecord(
                rs.getObject("id", UUID.class),
                rs.getString("alias"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("route_strategy"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String placeholders(String columns) {
        int count = columns.split(",").length;
        return "(" + "?,".repeat(count - 1) + "?)";
    }

    private String qualified() {
        return schemaName + ".model_alias";
    }

    protected static IllegalStateException translate(String message, SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if ("23505".equals(state)) {
            return new IllegalStateException("UNIQUE_VIOLATION: " + message, e);
        }
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
