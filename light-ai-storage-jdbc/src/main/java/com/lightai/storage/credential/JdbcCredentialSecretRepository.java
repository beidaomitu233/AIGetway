package com.lightai.storage.credential;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * credential_secret JDBC 仓储（DATABASE_PLAN §4）。
 * 一凭证一条安全记录；库账户普通查询服务无权读取秘密列（DB-006 边界），
 * 本类是产品内唯一允许绑定秘密字段的组件，日志禁止输出任何绑定值。
 */
public class JdbcCredentialSecretRepository {

    private final String schemaName;

    public JdbcCredentialSecretRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcCredentialSecretRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, SecretRecordRow row) {
        String sql = "INSERT INTO %s.credential_secret "
                + "(id, credential_id, secret_ciphertext, secret_ref_ciphertext, encryption_key_id, "
                + "masked_value, secret_version, rotated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                .formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, row.id());
            statement.setObject(2, row.credentialId());
            statement.setBytes(3, row.secretCiphertext());
            statement.setBytes(4, row.secretRefCiphertext());
            statement.setString(5, row.encryptionKeyId());
            statement.setString(6, row.maskedValue());
            statement.setLong(7, row.secretVersion());
            statement.setObject(8, row.rotatedAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("受保护秘密写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 轮换：覆盖密文并递增 secret_version，即时失效进程内旧句柄。 */
    public void rotate(Connection connection, UUID credentialId, byte[] newCiphertext,
                       byte[] newRefCiphertext, String encryptionKeyId, String maskedValue) {
        String sql = """
                UPDATE %s.credential_secret
                   SET secret_ciphertext = ?, secret_ref_ciphertext = ?, encryption_key_id = ?,
                       masked_value = ?, secret_version = secret_version + 1, rotated_at = now(),
                       updated_at = now()
                 WHERE credential_id = ?
                """.strip().formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, newCiphertext);
            statement.setBytes(2, newRefCiphertext);
            statement.setString(3, encryptionKeyId);
            statement.setString(4, maskedValue);
            statement.setObject(5, credentialId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("秘密轮换失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public Optional<SecretRecordRow> findByCredential(Connection connection, UUID credentialId) {
        String sql = "SELECT id, credential_id, secret_ciphertext, secret_ref_ciphertext, "
                + "encryption_key_id, masked_value, secret_version, rotated_at FROM " + qualified()
                + " WHERE credential_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, credentialId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SecretRecordRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("credential_id", UUID.class),
                        rs.getBytes("secret_ciphertext"),
                        rs.getBytes("secret_ref_ciphertext"),
                        rs.getString("encryption_key_id"),
                        rs.getString("masked_value"),
                        rs.getLong("secret_version"),
                        rs.getObject("rotated_at", OffsetDateTime.class)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("受保护秘密读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public void deleteByCredential(Connection connection, UUID credentialId) {
        String sql = "DELETE FROM " + qualified() + " WHERE credential_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, credentialId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("受保护秘密删除失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private String qualified() {
        return schemaName + ".credential_secret";
    }
}
