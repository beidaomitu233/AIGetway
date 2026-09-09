package com.lightai.storage.application;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 应用密钥仓储：摘要查询、应用范围列表、CAS 启停/轮换/撤销和活动摘要。 */
public final class JdbcApplicationKeyRepository extends AbstractJdbcRepository {

    private static final String COLUMNS = "id, application_id, name, key_prefix, masked_value, "
            + "key_digest, digest_version, rotation_generation, ip_allowlist, expires_at, rpm, tpm, status, "
            + "last_used_at, last_used_ip_masked, rotated_at, revoked_at, version, created_at, updated_at";

    public JdbcApplicationKeyRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcApplicationKeyRepository() {
        super();
    }

    public Optional<ApplicationKeyRecord> find(Connection connection, UUID id) {
        return findOne(connection, "id", id, null);
    }

    public Optional<ApplicationKeyRecord> findByDigest(Connection connection, byte[] digest) {
        return findOne(connection, "key_digest", null, digest);
    }

    private Optional<ApplicationKeyRecord> findOne(
            Connection connection, String column, UUID id, byte[] digest) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "application_key")
                + " WHERE " + column + " = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (id != null) dialect.bindUuid(statement, 1, id); else statement.setBytes(1, digest);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet, dialect)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("应用密钥读取失败", e);
        }
    }

    public boolean existsName(Connection connection, UUID applicationId, String name) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "application_key")
                + " WHERE application_id = ? AND name = ? AND status <> 'REVOKED' LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw failure("应用密钥名称检查失败", e);
        }
    }

    public void insert(Connection connection, ApplicationKeyRecord record) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "application_key") + " (" + COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + dialect.jsonPlaceholder()
                + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, " + dialect.nowFunction() + ", "
                + dialect.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            dialect.bindUuid(statement, i++, record.id());
            dialect.bindUuid(statement, i++, record.applicationId());
            statement.setString(i++, record.name());
            statement.setString(i++, record.keyPrefix());
            statement.setString(i++, record.maskedValue());
            statement.setBytes(i++, record.keyDigest());
            statement.setInt(i++, record.digestVersion());
            statement.setLong(i++, record.rotationGeneration());
            dialect.bindJson(statement, i++, toJson(record.ipAllowlist()));
            time(statement, i++, record.expiresAt());
            integer(statement, i++, record.rpm());
            bigint(statement, i++, record.tpm());
            statement.setString(i++, record.status());
            time(statement, i++, record.lastUsedAt());
            statement.setString(i++, record.lastUsedIpMasked());
            time(statement, i++, record.rotatedAt());
            time(statement, i++, record.revokedAt());
            statement.setLong(i, record.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw failure("应用密钥写入失败", e);
        }
    }

    public List<ApplicationKeyRecord> list(Connection connection, UUID applicationId) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "application_key")
                + " WHERE application_id = ? ORDER BY created_at DESC, id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ApplicationKeyRecord> records = new ArrayList<>();
                while (resultSet.next()) records.add(map(resultSet, dialect));
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw failure("应用密钥列表读取失败", e);
        }
    }

    public ApplicationKeyRecord replaceSecret(
            Connection connection, UUID id, String prefix, String maskedValue, byte[] digest,
            int digestVersion, OffsetDateTime now, long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application_key")
                + " SET key_prefix=?, masked_value=?, key_digest=?, digest_version=?, "
                + "rotation_generation=rotation_generation+1, rotated_at=?, status='ACTIVE', revoked_at=NULL, "
                + "version=version+1, updated_at=? WHERE id=? AND version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, prefix);
            statement.setString(2, maskedValue);
            statement.setBytes(3, digest);
            statement.setInt(4, digestVersion);
            time(statement, 5, now);
            time(statement, 6, now);
            dialect.bindUuid(statement, 7, id);
            statement.setLong(8, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
            return find(connection, id).orElseThrow();
        } catch (OptimisticLockException e) {
            throw e;
        } catch (SQLException e) {
            throw failure("应用密钥轮换失败", e);
        }
    }

    public ApplicationKeyRecord revoke(
            Connection connection, UUID id, OffsetDateTime now, long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application_key")
                + " SET status='REVOKED', revoked_at=?, version=version+1, updated_at=? "
                + "WHERE id=? AND version=? AND status <> 'REVOKED'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            time(statement, 1, now);
            time(statement, 2, now);
            dialect.bindUuid(statement, 3, id);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
            return find(connection, id).orElseThrow();
        } catch (OptimisticLockException e) {
            throw e;
        } catch (SQLException e) {
            throw failure("应用密钥撤销失败", e);
        }
    }

    public ApplicationKeyRecord updateStatus(
            Connection connection, UUID id, String status, OffsetDateTime now, long expectedVersion) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application_key")
                + " SET status=?, version=version+1, updated_at=? "
                + "WHERE id=? AND version=? AND status <> 'REVOKED'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            time(statement, 2, now);
            dialect.bindUuid(statement, 3, id);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) throw new OptimisticLockException();
            return find(connection, id).orElseThrow();
        } catch (OptimisticLockException e) {
            throw e;
        } catch (SQLException e) {
            throw failure("应用密钥状态更新失败", e);
        }
    }

    public void touch(Connection connection, UUID id, OffsetDateTime now, String maskedIp) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application_key")
                + " SET last_used_at=?, last_used_ip_masked=?, updated_at=? WHERE id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            time(statement, 1, now);
            statement.setString(2, maskedIp);
            time(statement, 3, now);
            dialect.bindUuid(statement, 4, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw failure("应用密钥活动摘要更新失败", e);
        }
    }

    private static ApplicationKeyRecord map(ResultSet rs, DatabaseDialect dialect) throws SQLException {
        return new ApplicationKeyRecord(
                dialect.readUuid(rs, "id"), dialect.readUuid(rs, "application_id"),
                rs.getString("name"), rs.getString("key_prefix"), rs.getString("masked_value"),
                rs.getBytes("key_digest"), rs.getInt("digest_version"),
                rs.getLong("rotation_generation"), fromJson(dialect.readJson(rs, "ip_allowlist")),
                dialect.readOffsetDateTime(rs, "expires_at"), intOrNull(rs, "rpm"),
                longOrNull(rs, "tpm"), rs.getString("status"),
                dialect.readOffsetDateTime(rs, "last_used_at"), rs.getString("last_used_ip_masked"),
                dialect.readOffsetDateTime(rs, "rotated_at"), dialect.readOffsetDateTime(rs, "revoked_at"),
                rs.getLong("version"), dialect.readOffsetDateTime(rs, "created_at"),
                dialect.readOffsetDateTime(rs, "updated_at"));
    }

    private static void time(PreparedStatement statement, int index, OffsetDateTime value) throws SQLException {
        if (value == null) statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        else statement.setTimestamp(index, Timestamp.from(value.toInstant()));
    }

    private static void integer(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
    }

    private static void bigint(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String toJson(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }

    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return List.of();
        String body = json.trim().substring(1, json.trim().length() - 1);
        if (body.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String part : body.split(",")) {
            values.add(part.trim().replaceFirst("^\"", "").replaceFirst("\"$", "")
                    .replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return List.copyOf(values);
    }

    private static IllegalStateException failure(String message, SQLException cause) {
        return new IllegalStateException(message + "：" + cause.getClass().getSimpleName(), cause);
    }

    public static final class OptimisticLockException extends RuntimeException {
    }
}
