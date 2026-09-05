package com.lightai.storage.alias;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** model_alias 表 JDBC 实现（DATABASE_PLAN §6）；U(alias) 活行唯一。 */
public final class JdbcModelAliasRepository implements ModelAliasRepository {

    private static final String COLUMNS = """
            id, alias, display_name, description, route_strategy, enabled,
            version, created_at, updated_at, deleted_at""";

    private final String schemaName;

    public JdbcModelAliasRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcModelAliasRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<ModelAliasRecord> find(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("model_alias 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<Long> findAliveVersion(Connection connection, UUID id) {
        String sql = "SELECT version FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("model_alias 版本读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public boolean existsAliveByAlias(Connection connection, String alias) {
        String sql = "SELECT 1 FROM " + qualified() + " WHERE alias = ? AND deleted_at IS NULL LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, alias);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("model_alias 唯一性检查失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void insert(Connection connection, ModelAliasRecord record) {
        String sql = "INSERT INTO " + qualified() + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("model_alias 写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void update(Connection connection, ModelAliasRecord record) {
        String sql = """
                UPDATE %s SET display_name=?, description=?, route_strategy=?, enabled=?, version=?,
                updated_at=?, deleted_at=? WHERE id=?""".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, record.displayName());
            statement.setString(i++, record.description());
            statement.setString(i++, record.routeStrategy());
            statement.setBoolean(i++, record.enabled());
            statement.setLong(i++, record.version());
            statement.setTimestamp(i++, Timestamp.from(record.updatedAt().toInstant()));
            statement.setTimestamp(i++, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
            statement.setObject(i, record.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("model_alias 更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<ModelAliasRecord> list(Connection connection, String filterSql, List<Object> filterValues,
                                       String orderSql, long offset, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE deleted_at IS NULL"
                + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql)
                + " ORDER BY " + orderSql + " OFFSET ? LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            for (Object value : filterValues) {
                statement.setObject(i++, value);
            }
            statement.setLong(i++, offset);
            statement.setInt(i, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<ModelAliasRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("model_alias 列表读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long count(Connection connection, String filterSql, List<Object> filterValues) {
        String sql = "SELECT count(*) FROM " + qualified() + " WHERE deleted_at IS NULL"
                + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            for (Object value : filterValues) {
                statement.setObject(i++, value);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("model_alias 计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static ModelAliasRecord mapRow(ResultSet rs) throws SQLException {
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        return new ModelAliasRecord(
                rs.getObject("id", UUID.class),
                rs.getString("alias"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("route_strategy"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at")),
                deletedAt == null ? null : offset(deletedAt));
    }

    private static void bind(PreparedStatement statement, ModelAliasRecord record) throws SQLException {
        statement.setObject(1, record.id());
        statement.setString(2, record.alias());
        statement.setString(3, record.displayName());
        statement.setString(4, record.description());
        statement.setString(5, record.routeStrategy());
        statement.setBoolean(6, record.enabled());
        statement.setLong(7, record.version());
        statement.setTimestamp(8, Timestamp.from(record.createdAt().toInstant()));
        statement.setTimestamp(9, Timestamp.from(record.updatedAt().toInstant()));
        statement.setTimestamp(10, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
    }

    private String qualified() {
        return schemaName + ".model_alias";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }
}
