package com.lightai.storage.credential;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * credential_secret JDBC 实现（DATABASE_PLAN §4）。
 * 异常信息只含异常类名；任何日志路径不得出现密文或掩码前值。
 */
public final class JdbcSecretRepository implements SecretRepository {

    private static final String COLUMNS = """
            credential_id, secret_ciphertext, secret_ref_ciphertext, encryption_key_id,
            masked_value, secret_version, rotated_at, updated_at""";

    private final String schemaName;

    public JdbcSecretRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcSecretRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Optional<SecretRecord> find(Connection connection, UUID credentialId) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE credential_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, credentialId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("credential_secret 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void upsert(Connection connection, SecretRecord record) {
        String sql = """
                INSERT INTO %s (credential_id, secret_ciphertext, secret_ref_ciphertext, encryption_key_id,
                                masked_value, secret_version, rotated_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (credential_id) DO UPDATE SET
                  secret_ciphertext = EXCLUDED.secret_ciphertext,
                  secret_ref_ciphertext = EXCLUDED.secret_ref_ciphertext,
                  encryption_key_id = EXCLUDED.encryption_key_id,
                  masked_value = EXCLUDED.masked_value,
                  secret_version = EXCLUDED.secret_version,
                  rotated_at = EXCLUDED.rotated_at,
                  updated_at = EXCLUDED.updated_at""".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, record.credentialId());
            statement.setBytes(2, record.secretCiphertext());
            statement.setBytes(3, record.secretRefCiphertext());
            statement.setString(4, record.encryptionKeyId());
            statement.setString(5, record.maskedValue());
            statement.setLong(6, record.secretVersion());
            statement.setTimestamp(7, record.rotatedAt() == null ? null : Timestamp.from(record.rotatedAt().toInstant()));
            statement.setTimestamp(8, Timestamp.from(record.updatedAt().toInstant()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("credential_secret 写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static SecretRecord mapRow(ResultSet rs) throws SQLException {
        Timestamp rotatedAt = rs.getTimestamp("rotated_at");
        return new SecretRecord(
                rs.getObject("credential_id", UUID.class),
                rs.getBytes("secret_ciphertext"),
                rs.getBytes("secret_ref_ciphertext"),
                rs.getString("encryption_key_id"),
                rs.getString("masked_value"),
                rs.getLong("secret_version"),
                rotatedAt == null ? null : OffsetDateTime.ofInstant(rotatedAt.toInstant(), java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(rs.getTimestamp("updated_at").toInstant(), java.time.ZoneOffset.UTC));
    }

    private String qualified() {
        return schemaName + ".credential_secret";
    }
}
