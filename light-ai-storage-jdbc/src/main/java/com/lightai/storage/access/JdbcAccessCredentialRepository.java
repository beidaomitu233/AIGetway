package com.lightai.storage.access;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
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
 * access_credential / access_credential_alias JDBC 实现（DATABASE_PLAN §36/§37）。
 * U(token_hash) 供业务鉴权；alias 白名单随实体同事务维护。
 */
public final class JdbcAccessCredentialRepository extends AbstractJdbcRepository implements AccessCredentialRepository {

    private static final String COLUMNS = """
            id, name, application, token_prefix, token_hash, token_hash_version, masked_value,
            ip_allowlist, expires_at, enabled, rotation_generation, issued_at, rotated_at,
            last_used_at, last_used_ip_masked, version, created_at, updated_at, deleted_at""";

    public JdbcAccessCredentialRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcAccessCredentialRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcAccessCredentialRepository() {
        super();
    }

    @Override
    public Optional<AccessCredentialRecord> find(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "access_credential") + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("access_credential 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<AccessCredentialRecord> findByTokenHash(Connection connection, byte[] tokenHash) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "access_credential")
                + " WHERE token_hash = ? AND deleted_at IS NULL LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, tokenHash);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("access_credential 鉴权读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public boolean existsAliveByName(Connection connection, String name) {
        String sql = "SELECT 1 FROM " + qualify(connection, "access_credential") + " WHERE name = ? AND deleted_at IS NULL LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("access_credential 名称检查失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void insert(Connection connection, AccessCredentialRecord record, List<UUID> allowedAliasIds) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "access_credential") + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record, d);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("access_credential 写入失败：" + e.getClass().getSimpleName(), e);
        }
        replaceAliases(connection, record.id(), allowedAliasIds);
    }

    @Override
    public void update(Connection connection, AccessCredentialRecord record, List<UUID> allowedAliasIds) {
        DatabaseDialect d = dialect(connection);
        String sql = ("UPDATE " + qualify(connection, "access_credential") + " SET name=?, application=?, token_prefix=?, token_hash=?, token_hash_version=?, "
                + "masked_value=?, ip_allowlist=?, expires_at=?, enabled=?, rotation_generation=?, issued_at=?, "
                + "rotated_at=?, last_used_at=?, last_used_ip_masked=?, version=?, updated_at=?, deleted_at=? "
                + "WHERE id=?").strip();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, record.name());
            statement.setString(i++, record.application());
            statement.setString(i++, record.tokenPrefix());
            statement.setBytes(i++, record.tokenHash());
            statement.setInt(i++, record.tokenHashVersion());
            statement.setString(i++, record.maskedValue());
            statement.setString(i++, toJson(record.ipAllowlist()));
            statement.setTimestamp(i++, record.expiresAt() == null ? null : Timestamp.from(record.expiresAt().toInstant()));
            statement.setBoolean(i++, record.enabled());
            statement.setLong(i++, record.rotationGeneration());
            statement.setTimestamp(i++, Timestamp.from(record.issuedAt().toInstant()));
            statement.setTimestamp(i++, record.rotatedAt() == null ? null : Timestamp.from(record.rotatedAt().toInstant()));
            statement.setTimestamp(i++, record.lastUsedAt() == null ? null : Timestamp.from(record.lastUsedAt().toInstant()));
            statement.setString(i++, record.lastUsedIpMasked());
            statement.setLong(i++, record.version());
            statement.setTimestamp(i++, Timestamp.from(record.updatedAt().toInstant()));
            statement.setTimestamp(i++, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
            d.bindUuid(statement, i, record.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("access_credential 更新失败：" + e.getClass().getSimpleName(), e);
        }
        replaceAliases(connection, record.id(), allowedAliasIds);
    }

    private void replaceAliases(Connection connection, UUID credentialId, List<UUID> aliasIds) {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + qualify(connection, "access_credential_alias") + " WHERE access_credential_id = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + qualify(connection, "access_credential_alias") + " (id, access_credential_id, alias_id, created_at) VALUES (?,?,?,?)")) {
            d.bindUuid(delete, 1, credentialId);
            delete.executeUpdate();
            OffsetDateTime now = OffsetDateTime.now();
            for (UUID aliasId : aliasIds == null ? List.<UUID>of() : aliasIds) {
                d.bindUuid(insert, 1, UUID.randomUUID());
                d.bindUuid(insert, 2, credentialId);
                d.bindUuid(insert, 3, aliasId);
                insert.setTimestamp(4, Timestamp.from(now.toInstant()));
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("access_credential_alias 维护失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<UUID> aliasIdsOf(Connection connection, UUID credentialId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT alias_id FROM " + qualify(connection, "access_credential_alias") + " WHERE access_credential_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, credentialId);
            try (ResultSet rs = statement.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(d.readUuid(rs, 1));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("alias 白名单读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<AccessCredentialRecord> list(Connection connection, String filterSql, List<Object> filterValues,
                                             String orderSql, long offset, int limit) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "access_credential") + " WHERE deleted_at IS NULL"
                + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql)
                + " ORDER BY " + orderSql + " LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, filterValues, d);
            int i = filterValues.size() + 1;
            statement.setInt(i++, limit);
            statement.setLong(i, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<AccessCredentialRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs, d));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("access_credential 列表读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long count(Connection connection, String filterSql, List<Object> filterValues) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT count(*) FROM " + qualify(connection, "access_credential") + " WHERE deleted_at IS NULL"
                + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, filterValues, d);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("access_credential 计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void touch(Connection connection, UUID id, OffsetDateTime usedAt, String maskedIp) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "access_credential")
                + " SET last_used_at = ?, last_used_ip_masked = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(usedAt.toInstant()));
            statement.setString(2, maskedIp);
            statement.setTimestamp(3, Timestamp.from(usedAt.toInstant()));
            d.bindUuid(statement, 4, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("access_credential 活动摘要更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static AccessCredentialRecord mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new AccessCredentialRecord(
                d.readUuid(rs, "id"),
                rs.getString("name"),
                rs.getString("application"),
                rs.getString("token_prefix"),
                rs.getBytes("token_hash"),
                rs.getInt("token_hash_version"),
                rs.getString("masked_value"),
                fromJson(rs.getString("ip_allowlist")),
                d.readOffsetDateTime(rs, "expires_at"),
                rs.getBoolean("enabled"),
                rs.getLong("rotation_generation"),
                d.readOffsetDateTime(rs, "issued_at"),
                d.readOffsetDateTime(rs, "rotated_at"),
                d.readOffsetDateTime(rs, "last_used_at"),
                rs.getString("last_used_ip_masked"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"),
                d.readOffsetDateTime(rs, "deleted_at"));
    }

    private static void bind(PreparedStatement statement, AccessCredentialRecord record, DatabaseDialect d) throws SQLException {
        int i = 1;
        d.bindUuid(statement, i++, record.id());
        statement.setString(i++, record.name());
        statement.setString(i++, record.application());
        statement.setString(i++, record.tokenPrefix());
        statement.setBytes(i++, record.tokenHash());
        statement.setInt(i++, record.tokenHashVersion());
        statement.setString(i++, record.maskedValue());
        statement.setString(i++, toJson(record.ipAllowlist()));
        statement.setTimestamp(i++, record.expiresAt() == null ? null : Timestamp.from(record.expiresAt().toInstant()));
        statement.setBoolean(i++, record.enabled());
        statement.setLong(i++, record.rotationGeneration());
        statement.setTimestamp(i++, Timestamp.from(record.issuedAt().toInstant()));
        statement.setTimestamp(i++, record.rotatedAt() == null ? null : Timestamp.from(record.rotatedAt().toInstant()));
        statement.setTimestamp(i++, record.lastUsedAt() == null ? null : Timestamp.from(record.lastUsedAt().toInstant()));
        statement.setString(i++, record.lastUsedIpMasked());
        statement.setLong(i++, record.version());
        statement.setTimestamp(i++, Timestamp.from(record.createdAt().toInstant()));
        statement.setTimestamp(i++, Timestamp.from(record.updatedAt().toInstant()));
        statement.setTimestamp(i, record.deletedAt() == null ? null : Timestamp.from(record.deletedAt().toInstant()));
    }

    private static String toJson(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < (values == null ? 0 : values.size()); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }

    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        String body = json.trim();
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : body.split(",")) {
            values.add(part.trim().replaceFirst("^\"", "").replaceFirst("\"$", "").replace("\\\"", "\""));
        }
        return List.copyOf(values);
    }
}
