package com.lightai.storage.credential;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * credential JDBC 仓储（DATABASE_PLAN §3）。
 * 池内名称唯一（活行）；credential_secret 为独立受保护表，本仓储不触碰秘密列。
 * 支持 PostgreSQL 与 MySQL 5.7 / 8.0 双方言自适应。
 */
public class JdbcCredentialRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, pool_id, name, secret_source, weight, rpm_limit, tpm_limit, concurrent_limit, "
                    + "enabled, version, created_at, updated_at";

    public JdbcCredentialRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcCredentialRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcCredentialRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, CredentialRecord record) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "credential") + " (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record, d);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("凭证写入失败", e);
        }
    }

    private void bind(PreparedStatement statement, CredentialRecord record, DatabaseDialect d) throws SQLException {
        d.bindUuid(statement, 1, record.id());
        d.bindUuid(statement, 2, record.poolId());
        statement.setString(3, record.name());
        statement.setString(4, record.secretSource());
        statement.setInt(5, record.weight());
        statement.setObject(6, record.rpmLimit());
        statement.setObject(7, record.tpmLimit());
        statement.setObject(8, record.concurrentLimit());
        statement.setBoolean(9, record.enabled());
        statement.setLong(10, record.version());
    }

    public Optional<CredentialRecord> findLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "credential")
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("凭证读取失败", e);
        }
    }

    public Optional<CredentialRecord> lockLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "credential")
                + " WHERE id = ? AND deleted_at IS NULL " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("凭证锁定失败", e);
        }
    }

    public boolean existsByLiveNameInPool(Connection connection, UUID poolId, String name) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "credential")
                + " WHERE pool_id = ? AND name = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, poolId);
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("凭证名称检查失败", e);
        }
    }

    public CredentialRecord update(Connection connection, CredentialRecord record) {
        DatabaseDialect d = dialect(connection);
        if (d.supportsReturning()) {
            String sql = """
                    UPDATE %s
                       SET name = ?, weight = ?, rpm_limit = ?, tpm_limit = ?, concurrent_limit = ?,
                           enabled = ?, version = version + 1, updated_at = %s
                     WHERE id = ? AND deleted_at IS NULL
                    RETURNING %s
                    """.strip().formatted(qualify(connection, "credential"), d.nowFunction(), COLUMNS);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.name());
                statement.setInt(2, record.weight());
                statement.setObject(3, record.rpmLimit());
                statement.setObject(4, record.tpmLimit());
                statement.setObject(5, record.concurrentLimit());
                statement.setBoolean(6, record.enabled());
                d.bindUuid(statement, 7, record.id());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("凭证更新未命中活行");
                    }
                    return mapRow(rs, d);
                }
            } catch (SQLException e) {
                throw translate("凭证更新失败", e);
            }
        } else {
            String sql = "UPDATE " + qualify(connection, "credential")
                    + " SET name = ?, weight = ?, rpm_limit = ?, tpm_limit = ?, concurrent_limit = ?, "
                    + "enabled = ?, version = version + 1, updated_at = " + d.nowFunction()
                    + " WHERE id = ? AND deleted_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.name());
                statement.setInt(2, record.weight());
                statement.setObject(3, record.rpmLimit());
                statement.setObject(4, record.tpmLimit());
                statement.setObject(5, record.concurrentLimit());
                statement.setBoolean(6, record.enabled());
                d.bindUuid(statement, 7, record.id());
                int affected = statement.executeUpdate();
                if (affected == 0) {
                    throw new IllegalStateException("凭证更新未命中活行");
                }
                return findLiveById(connection, record.id())
                        .orElseThrow(() -> new IllegalStateException("凭证更新后未找到活行"));
            } catch (SQLException e) {
                throw translate("凭证更新失败", e);
            }
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "credential")
                + " SET deleted_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("凭证删除失败", e);
        }
    }

    public List<CredentialRecord> listByPool(Connection connection, UUID poolId,
                                             String healthStatus, Boolean enabled,
                                             String sortExpression, int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualify(connection, "credential")).append(" c WHERE c.pool_id = ? AND c.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(poolId);
        if (enabled != null) {
            sql.append(" AND c.enabled = ?");
            params.add(enabled);
        }
        if (healthStatus != null && !healthStatus.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(qualify(connection, "object_runtime_state"))
                    .append(" s WHERE s.entity_type = 'CREDENTIAL'")
                    .append(" AND s.entity_id = c.id AND s.health_status = ?)");
            params.add(healthStatus.strip());
        }
        sql.append(" ORDER BY c.").append(sortExpression).append(", c.id ASC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<CredentialRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs, d));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("凭证列表查询失败", e);
        }
    }

    public long countByPool(Connection connection, UUID poolId, String healthStatus, Boolean enabled) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualify(connection, "credential"))
                .append(" c WHERE c.pool_id = ? AND c.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(poolId);
        if (enabled != null) {
            sql.append(" AND c.enabled = ?");
            params.add(enabled);
        }
        if (healthStatus != null && !healthStatus.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(qualify(connection, "object_runtime_state"))
                    .append(" s WHERE s.entity_type = 'CREDENTIAL'")
                    .append(" AND s.entity_id = c.id AND s.health_status = ?)");
            params.add(healthStatus.strip());
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("凭证计数失败", e);
        }
    }

    private CredentialRecord mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new CredentialRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "pool_id"),
                rs.getString("name"),
                rs.getString("secret_source"),
                rs.getInt("weight"),
                getLongOrNull(rs, "rpm_limit"),
                getLongOrNull(rs, "tpm_limit"),
                getIntOrNull(rs, "concurrent_limit"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }
}
