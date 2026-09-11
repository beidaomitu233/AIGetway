package com.lightai.storage.channel;

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
 * channel_credential JDBC 仓储（DATABASE_PLAN §3）。
 * 渠道内名称唯一（活行）；密钥密文（原 credential_secret 保护列）折叠进本表，
 * 明文永不出库，仅以 AES-GCM 密文与掩码承载。
 * 支持 PostgreSQL 与 MySQL 5.7 / 8.0 双方言自适应。
 */
public class JdbcChannelCredentialRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, channel_id, name, secret_ciphertext, secret_ref_ciphertext, key_id, masked_value, "
                    + "secret_version, rotated_at, priority, weight, rpm_limit, tpm_limit, concurrent_limit, "
                    + "status, health, version, created_at, updated_at";

    /** JOIN 场景（可调度渠道 Key 查询）下的带前缀列清单，避免与 object_runtime_state 的 id 歧义。 */
    private static final String PREFIXED_COLUMNS =
            "c.id, c.channel_id, c.name, c.secret_ciphertext, c.secret_ref_ciphertext, c.key_id, c.masked_value, "
                    + "c.secret_version, c.rotated_at, c.priority, c.weight, c.rpm_limit, c.tpm_limit, c.concurrent_limit, "
                    + "c.status, c.health, c.version, c.created_at, c.updated_at";

    public JdbcChannelCredentialRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcChannelCredentialRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcChannelCredentialRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, ChannelCredentialRecord record) {
        DatabaseDialect d = dialect(connection);
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at"));
        String placeholders = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + d.nowFunction() + ", " + d.nowFunction();
        String sql = "INSERT INTO " + qualify(connection, "channel_credential") + " (" + insertColumns + ") "
                + "VALUES (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, record, d);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("渠道 Key 写入失败", e);
        }
    }

    private void bind(PreparedStatement statement, ChannelCredentialRecord record, DatabaseDialect d) throws SQLException {
        d.bindUuid(statement, 1, record.id());
        d.bindUuid(statement, 2, record.channelId());
        statement.setString(3, record.name());
        statement.setBytes(4, record.secretCiphertext());
        statement.setBytes(5, record.secretRefCiphertext());
        statement.setString(6, record.keyId());
        statement.setString(7, record.maskedValue());
        statement.setLong(8, record.secretVersion());
        statement.setObject(9, record.rotatedAt());
        statement.setInt(10, record.priority());
        statement.setInt(11, record.weight());
        statement.setObject(12, record.rpmLimit());
        statement.setObject(13, record.tpmLimit());
        statement.setObject(14, record.concurrentLimit());
        statement.setString(15, record.status());
        statement.setString(16, record.health());
        statement.setLong(17, record.version());
    }

    public Optional<ChannelCredentialRecord> findLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "channel_credential")
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("渠道 Key 读取失败", e);
        }
    }

    public Optional<ChannelCredentialRecord> lockLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "channel_credential")
                + " WHERE id = ? AND deleted_at IS NULL " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("渠道 Key 锁定失败", e);
        }
    }

    public boolean existsByLiveNameInChannel(Connection connection, UUID channelId, String name) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "channel_credential")
                + " WHERE channel_id = ? AND name = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, channelId);
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("渠道 Key 名称检查失败", e);
        }
    }

    /** 更新可编辑配置字段（名称/优先级/权重/限额/状态），version+1。 */
    public ChannelCredentialRecord update(Connection connection, ChannelCredentialRecord record) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "channel_credential")
                + " SET name = ?, priority = ?, weight = ?, rpm_limit = ?, tpm_limit = ?, concurrent_limit = ?, "
                + "status = ?, version = version + 1, updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.name());
            statement.setInt(2, record.priority());
            statement.setInt(3, record.weight());
            statement.setObject(4, record.rpmLimit());
            statement.setObject(5, record.tpmLimit());
            statement.setObject(6, record.concurrentLimit());
            statement.setString(7, record.status());
            d.bindUuid(statement, 8, record.id());
            int affected = statement.executeUpdate();
            if (affected == 0) {
                throw new IllegalStateException("渠道 Key 更新未命中活行");
            }
            return findLiveById(connection, record.id())
                    .orElseThrow(() -> new IllegalStateException("渠道 Key 更新后未找到活行"));
        } catch (SQLException e) {
            throw translate("渠道 Key 更新失败", e);
        }
    }

    /** 启停：status 在 ACTIVE/DISABLED 间切换，version+1。 */
    public ChannelCredentialRecord setEnabled(Connection connection, UUID id, boolean enabled) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "channel_credential")
                + " SET status = ?, version = version + 1, updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, enabled ? ChannelCredentialRecord.STATUS_ACTIVE : ChannelCredentialRecord.STATUS_DISABLED);
            d.bindUuid(statement, 2, id);
            int affected = statement.executeUpdate();
            if (affected == 0) {
                throw new IllegalStateException("渠道 Key 启停未命中活行");
            }
            return findLiveById(connection, id).orElseThrow(() -> new IllegalStateException("渠道 Key 启停未命中活行"));
        } catch (SQLException e) {
            throw translate("渠道 Key 启停失败", e);
        }
    }

    /**
     * 写入/轮换密钥密文：secret_version+1、rotated_at=now、更新掩码；
     * secret_source 不可切换，因此只写入与既有来源对应的列。
     */
    public void updateSecret(Connection connection, UUID id, byte[] ciphertext, byte[] refCiphertext,
                             String keyId, String maskedValue) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "channel_credential")
                + " SET secret_ciphertext = ?, secret_ref_ciphertext = ?, key_id = ?, masked_value = ?, "
                + "secret_version = secret_version + 1, rotated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, ciphertext);
            statement.setBytes(2, refCiphertext);
            statement.setString(3, keyId);
            statement.setString(4, maskedValue);
            d.bindUuid(statement, 5, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("渠道 Key 密文写入失败", e);
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "channel_credential")
                + " SET deleted_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("渠道 Key 删除失败", e);
        }
    }

    public List<ChannelCredentialRecord> listByChannel(Connection connection, UUID channelId,
                                                      String healthStatus, Boolean enabled,
                                                      String sortExpression, int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualify(connection, "channel_credential")).append(" c WHERE c.channel_id = ? AND c.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(channelId);
        if (enabled != null) {
            sql.append(" AND c.status = ?");
            params.add(enabled ? ChannelCredentialRecord.STATUS_ACTIVE : ChannelCredentialRecord.STATUS_DISABLED);
        }
        if (healthStatus != null && !healthStatus.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(qualify(connection, "object_runtime_state"))
                    .append(" s WHERE s.entity_type = 'CHANNEL_CREDENTIAL'")
                    .append(" AND s.entity_id = c.id AND s.health_status = ?)");
            params.add(healthStatus.strip());
        }
        sql.append(" ORDER BY c.").append(sortExpression).append(", c.id ASC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChannelCredentialRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs, d));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("渠道 Key 列表查询失败", e);
        }
    }

    /**
     * 渠道内可参与调度的 Key（PRD 4.3.4 / 9：UNKNOWN 可参与选择但优先级低于 HEALTHY，
     * RATE_LIMITED 在复位时间前不参与，DISABLED/INVALID/UNAVAILABLE 不参与）。
     * 无 object_runtime_state 记录按 UNKNOWN 处理，避免新建 Key 无法参与调度。
     */
    public List<ChannelCredentialRecord> listSelectableByChannel(Connection connection, UUID channelId,
                                                                String sortExpression, int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + PREFIXED_COLUMNS + " FROM " + qualify(connection, "channel_credential") + " c "
                + "LEFT JOIN " + qualify(connection, "object_runtime_state") + " s "
                + "ON s.entity_type = 'CHANNEL_CREDENTIAL' AND s.entity_id = c.id "
                + "WHERE c.channel_id = ? AND c.deleted_at IS NULL AND c.status = ? "
                + "AND COALESCE(s.health_status, 'UNKNOWN') NOT IN ('DISABLED', 'INVALID', 'UNAVAILABLE') "
                + "AND NOT (COALESCE(s.health_status, 'UNKNOWN') = 'RATE_LIMITED' "
                + "AND (s.reset_at IS NULL OR s.reset_at > " + d.nowFunction() + ")) "
                + "ORDER BY CASE COALESCE(s.health_status, 'UNKNOWN') WHEN 'HEALTHY' THEN 0 ELSE 1 END, c."
                + sortExpression + ", c.id ASC LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, channelId);
            statement.setString(2, ChannelCredentialRecord.STATUS_ACTIVE);
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChannelCredentialRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs, d));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("可调度渠道 Key 查询失败", e);
        }
    }

    public long countByChannel(Connection connection, UUID channelId, String healthStatus, Boolean enabled) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualify(connection, "channel_credential"))
                .append(" c WHERE c.channel_id = ? AND c.deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(channelId);
        if (enabled != null) {
            sql.append(" AND c.status = ?");
            params.add(enabled ? ChannelCredentialRecord.STATUS_ACTIVE : ChannelCredentialRecord.STATUS_DISABLED);
        }
        if (healthStatus != null && !healthStatus.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(qualify(connection, "object_runtime_state"))
                    .append(" s WHERE s.entity_type = 'CHANNEL_CREDENTIAL'")
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
            throw translate("渠道 Key 计数失败", e);
        }
    }

    private ChannelCredentialRecord mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new ChannelCredentialRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "channel_id"),
                rs.getString("name"),
                rs.getBytes("secret_ciphertext"),
                rs.getBytes("secret_ref_ciphertext"),
                rs.getString("key_id"),
                rs.getString("masked_value"),
                rs.getLong("secret_version"),
                rs.getObject("rotated_at") == null ? null : d.readOffsetDateTime(rs, "rotated_at"),
                rs.getInt("priority"),
                rs.getInt("weight"),
                getLongOrNull(rs, "rpm_limit"),
                getLongOrNull(rs, "tpm_limit"),
                getIntOrNull(rs, "concurrent_limit"),
                rs.getString("status"),
                rs.getString("health"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }
}
