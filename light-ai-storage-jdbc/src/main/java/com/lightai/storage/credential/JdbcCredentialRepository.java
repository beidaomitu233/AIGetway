package com.lightai.storage.credential;

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
 * credential JDBC 仓储（DATABASE_PLAN §3）。
 * 池内名称唯一（活行）；credential_secret 为独立受保护表，本仓储不触碰秘密列。
 */
public class JdbcCredentialRepository {

    private static final String COLUMNS =
            "id, pool_id, name, secret_source, weight, rpm_limit, tpm_limit, concurrent_limit, "
                    + "enabled, version, created_at, updated_at";

    protected final String schemaName;

    public JdbcCredentialRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcCredentialRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, CredentialRecord record) {
        String sql = "INSERT INTO %s.credential (%s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())"
                .formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("凭证写入失败", e);
        }
    }

    private void bind(PreparedStatement statement, CredentialRecord record) throws SQLException {
        statement.setObject(1, record.id());
        statement.setObject(2, record.poolId());
        statement.setString(3, record.name());
        statement.setString(4, record.secretSource());
        statement.setInt(5, record.weight());
        statement.setLong(6, record.rpmLimit());
        statement.setLong(7, record.tpmLimit());
        statement.setInt(8, record.concurrentLimit());
        statement.setBoolean(9, record.enabled());
        statement.setLong(10, record.version());
    }

    public Optional<CredentialRecord> findLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("凭证读取失败", e);
        }
    }

    public Optional<CredentialRecord> lockLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE id = ? AND deleted_at IS NULL FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("凭证锁定失败", e);
        }
    }

    public boolean existsByLiveNameInPool(Connection connection, UUID poolId, String name) {
        String sql = "SELECT 1 FROM " + qualified() + " WHERE pool_id = ? AND name = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, poolId);
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("凭证名称检查失败", e);
        }
    }

    public CredentialRecord update(Connection connection, CredentialRecord record) {
        String sql = """
                UPDATE %s.credential
                   SET name = ?, weight = ?, rpm_limit = ?, tpm_limit = ?, concurrent_limit = ?,
                       enabled = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                RETURNING %s
                """.strip().formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.name());
            statement.setInt(2, record.weight());
            statement.setLong(3, record.rpmLimit());
            statement.setLong(4, record.tpmLimit());
            statement.setInt(5, record.concurrentLimit());
            statement.setBoolean(6, record.enabled());
            statement.setObject(7, record.id());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("凭证更新未命中活行");
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw translate("凭证更新失败", e);
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        String sql = "UPDATE %s.credential SET deleted_at = now(), updated_at = now() "
                + "WHERE id = ? AND deleted_at IS NULL".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("凭证删除失败", e);
        }
    }

    public List<CredentialRecord> listByPool(Connection connection, UUID poolId,
                                             String healthStatus, Boolean enabled,
                                             String sortExpression, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualified()).append(" WHERE pool_id = ? AND deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(poolId);
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        if (healthStatus != null && !healthStatus.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(schemaName)
                    .append(".object_runtime_state s WHERE s.entity_type = 'CREDENTIAL'")
                    .append(" AND s.entity_id = credential.id AND s.health_status = ?)");
            params.add(healthStatus.strip());
        }
        sql.append(" ORDER BY ").append(sortExpression).append(", id ASC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<CredentialRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("凭证列表查询失败", e);
        }
    }

    public long countByPool(Connection connection, UUID poolId, String healthStatus, Boolean enabled) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualified())
                .append(" WHERE pool_id = ? AND deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(poolId);
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        if (healthStatus != null && !healthStatus.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(schemaName)
                    .append(".object_runtime_state s WHERE s.entity_type = 'CREDENTIAL'")
                    .append(" AND s.entity_id = credential.id AND s.health_status = ?)");
            params.add(healthStatus.strip());
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
            throw translate("凭证计数失败", e);
        }
    }

    private CredentialRecord mapRow(ResultSet rs) throws SQLException {
        return new CredentialRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("pool_id", UUID.class),
                rs.getString("name"),
                rs.getString("secret_source"),
                rs.getInt("weight"),
                (Long) rs.getObject("rpm_limit"),
                (Long) rs.getObject("tpm_limit"),
                (Integer) rs.getObject("concurrent_limit"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String qualified() {
        return schemaName + ".credential";
    }

    protected static IllegalStateException translate(String message, SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if ("23505".equals(state)) {
            return new IllegalStateException("UNIQUE_VIOLATION: " + message, e);
        }
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
