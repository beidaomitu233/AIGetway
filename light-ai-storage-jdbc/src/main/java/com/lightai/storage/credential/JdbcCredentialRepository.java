package com.lightai.storage.credential;

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

/**
 * credential 表 JDBC 实现（DATABASE_PLAN §3）。
 * 全部查询限定 deleted_at IS NULL；SQL 异常映射为确定异常，不打印绑定值。
 */
public final class JdbcCredentialRepository implements CredentialRepository {

    private static final String COLUMNS = """
            id, pool_id, name, secret_source, weight, rpm_limit, tpm_limit, concurrent_limit,
            enabled, version, created_at, updated_at, deleted_at""";

    private final String schemaName;

    public JdbcCredentialRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcCredentialRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<CredentialRecord> find(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("credential 读取失败：" + e.getClass().getSimpleName(), e);
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
            throw new IllegalStateException("credential 版本读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public boolean existsAliveByName(Connection connection, UUID poolId, String name) {
        String sql = "SELECT 1 FROM " + qualified()
                + " WHERE pool_id = ? AND name = ? AND deleted_at IS NULL LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, poolId);
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("credential 名称检查失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void insert(Connection connection, CredentialRecord record) {
        String sql = "INSERT INTO " + qualified() + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("credential 写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void update(Connection connection, CredentialRecord record) {
        String sql = """
                UPDATE %s SET pool_id=?, name=?, secret_source=?, weight=?, rpm_limit=?, tpm_limit=?,
                concurrent_limit=?, enabled=?, version=?, updated_at=?, deleted_at=?
                WHERE id=?""".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setObject(i++, record.poolId());
            statement.setString(i++, record.name());
            statement.setString(i++, record.secretSource());
            statement.setInt(i++, record.weight());
            setNullableLong(statement, i++, record.rpmLimit());
            setNullableLong(statement, i++, record.tpmLimit());
            setNullableInt(statement, i++, record.concurrentLimit());
            statement.setBoolean(i++, record.enabled());
            statement.setLong(i++, record.version());
            statement.setTimestamp(i++, Timestamp.from(record.updatedAt().toInstant()));
            statement.setTimestamp(i++, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
            statement.setObject(i, record.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("credential 更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<CredentialRow> listByPool(Connection connection, UUID poolId, String filterSql,
                                          List<Object> filterValues, String orderSql, long offset, int limit) {
        String sql = "SELECT c." + COLUMNS.replace(", ", ", c.") + ", s.masked_value FROM "
                + qualified() + " c LEFT JOIN " + secretQualified() + " s ON s.credential_id = c.id"
                + " WHERE c.pool_id = ? AND c.deleted_at IS NULL"
                + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql)
                + " ORDER BY c." + orderSql + " OFFSET ? LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setObject(i++, poolId);
            for (Object value : filterValues) {
                statement.setObject(i++, value);
            }
            statement.setLong(i++, offset);
            statement.setInt(i, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<CredentialRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new CredentialRow(mapRow(rs), rs.getString("masked_value")));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("credential 列表读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long countByPool(Connection connection, UUID poolId, String filterSql, List<Object> filterValues) {
        String sql = "SELECT count(*) FROM " + qualified()
                + " WHERE pool_id = ? AND deleted_at IS NULL"
                + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setObject(i++, poolId);
            for (Object value : filterValues) {
                statement.setObject(i++, value);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("credential 计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<CredentialRecord> findAliveByIds(Connection connection, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
        }
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE deleted_at IS NULL AND id IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                statement.setObject(i + 1, ids.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<CredentialRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("credential 批量读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static CredentialRecord mapRow(ResultSet rs) throws SQLException {
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        return new CredentialRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("pool_id", UUID.class),
                rs.getString("name"),
                rs.getString("secret_source"),
                rs.getInt("weight"),
                getNullableLong(rs, "rpm_limit"),
                getNullableLong(rs, "tpm_limit"),
                getNullableInteger(rs, "concurrent_limit"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at")),
                deletedAt == null ? null : offset(deletedAt));
    }

    private static void bind(PreparedStatement statement, CredentialRecord record) throws SQLException {
        int i = 1;
        statement.setObject(i++, record.id());
        statement.setObject(i++, record.poolId());
        statement.setString(i++, record.name());
        statement.setString(i++, record.secretSource());
        statement.setInt(i++, record.weight());
        setNullableLong(statement, i++, record.rpmLimit());
        setNullableLong(statement, i++, record.tpmLimit());
        setNullableInt(statement, i++, record.concurrentLimit());
        statement.setBoolean(i++, record.enabled());
        statement.setLong(i++, record.version());
        statement.setTimestamp(i++, Timestamp.from(record.createdAt().toInstant()));
        statement.setTimestamp(i++, Timestamp.from(record.updatedAt().toInstant()));
        statement.setTimestamp(i, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
    }

    private String qualified() {
        return schemaName + ".credential";
    }

    private String secretQualified() {
        return schemaName + ".credential_secret";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer getNullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }
}
