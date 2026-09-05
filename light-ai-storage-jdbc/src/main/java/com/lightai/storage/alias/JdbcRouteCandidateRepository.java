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

/** route_candidate 表 JDBC 实现（DATABASE_PLAN §7）；U(alias_id, provider_model_id, credential_pool_id) 活行唯一。 */
public final class JdbcRouteCandidateRepository implements RouteCandidateRepository {

    private static final String COLUMNS = """
            id, alias_id, provider_model_id, credential_pool_id, priority, weight, enabled,
            version, created_at, updated_at, deleted_at""";

    private final String schemaName;

    public JdbcRouteCandidateRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcRouteCandidateRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<RouteCandidateRecord> find(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("route_candidate 读取失败：" + e.getClass().getSimpleName(), e);
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
            throw new IllegalStateException("route_candidate 版本读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public boolean existsAliveByTriple(Connection connection, UUID aliasId, UUID providerModelId, UUID credentialPoolId) {
        String sql = "SELECT 1 FROM " + qualified() + """
                WHERE alias_id = ? AND provider_model_id = ? AND credential_pool_id = ?
                AND deleted_at IS NULL LIMIT 1""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, aliasId);
            statement.setObject(2, providerModelId);
            statement.setObject(3, credentialPoolId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("route_candidate 重复检查失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void insert(Connection connection, RouteCandidateRecord record) {
        String sql = "INSERT INTO " + qualified() + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("route_candidate 写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void update(Connection connection, RouteCandidateRecord record) {
        String sql = """
                UPDATE %s SET credential_pool_id=?, priority=?, weight=?, enabled=?, version=?,
                updated_at=?, deleted_at=? WHERE id=?""".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setObject(i++, record.credentialPoolId());
            statement.setInt(i++, record.priority());
            statement.setInt(i++, record.weight());
            statement.setBoolean(i++, record.enabled());
            statement.setLong(i++, record.version());
            statement.setTimestamp(i++, Timestamp.from(record.updatedAt().toInstant()));
            statement.setTimestamp(i++, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
            statement.setObject(i, record.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("route_candidate 更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void updatePriority(Connection connection, UUID id, int priority, long newVersion) {
        String sql = "UPDATE " + qualified()
                + " SET priority = ?, version = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, priority);
            statement.setLong(2, newVersion);
            statement.setTimestamp(3, Timestamp.from(OffsetDateTime.now().toInstant()));
            statement.setObject(4, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("route_candidate 重排写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<RouteCandidateRecord> listByAlias(Connection connection, UUID aliasId, String orderSql) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE alias_id = ? AND deleted_at IS NULL ORDER BY " + orderSql;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, aliasId);
            try (ResultSet rs = statement.executeQuery()) {
                List<RouteCandidateRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("route_candidate 列表读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<RouteCandidateRecord> findAliveByModelIds(Connection connection, List<UUID> modelIds) {
        return findByUuidColumn(connection, "provider_model_id", modelIds);
    }

    @Override
    public List<RouteCandidateRecord> findAliveByPoolIds(Connection connection, List<UUID> poolIds) {
        return findByUuidColumn(connection, "credential_pool_id", poolIds);
    }

    private List<RouteCandidateRecord> findByUuidColumn(Connection connection, String column, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
        }
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE deleted_at IS NULL AND " + column + " IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                statement.setObject(i + 1, ids.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<RouteCandidateRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("route_candidate 引用读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static RouteCandidateRecord mapRow(ResultSet rs) throws SQLException {
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        return new RouteCandidateRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("alias_id", UUID.class),
                rs.getObject("provider_model_id", UUID.class),
                rs.getObject("credential_pool_id", UUID.class),
                rs.getInt("priority"),
                rs.getInt("weight"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at")),
                deletedAt == null ? null : offset(deletedAt));
    }

    private static void bind(PreparedStatement statement, RouteCandidateRecord record) throws SQLException {
        statement.setObject(1, record.id());
        statement.setObject(2, record.aliasId());
        statement.setObject(3, record.providerModelId());
        statement.setObject(4, record.credentialPoolId());
        statement.setInt(5, record.priority());
        statement.setInt(6, record.weight());
        statement.setBoolean(7, record.enabled());
        statement.setLong(8, record.version());
        statement.setTimestamp(9, Timestamp.from(record.createdAt().toInstant()));
        statement.setTimestamp(10, Timestamp.from(record.updatedAt().toInstant()));
        statement.setTimestamp(11, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
    }

    private String qualified() {
        return schemaName + ".route_candidate";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }
}
