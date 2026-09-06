package com.lightai.storage.runtime;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.dialect.DatabaseType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 运行状态写入（BE-009：检测即时更新运行状态，不进草稿）。
 * state_version 以自增维护 CAS 基线；connection_status 仅由检测与调用结果驱动。
 */
public class JdbcRuntimeStateWriter extends AbstractJdbcRepository {

    public JdbcRuntimeStateWriter(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcRuntimeStateWriter(String schemaName) {
        super(schemaName);
    }

    public JdbcRuntimeStateWriter() {
        super();
    }

    /** 检测/调用结束后的 Provider 状态收敛（幂等 upsert）。 */
    public void upsertProviderState(Connection connection, UUID providerId, String connectionStatus,
                                    OffsetDateTime checkedAt, String errorCode, String errorSummary) {
        DatabaseDialect d = dialect(connection);
        String sql;
        if (d.databaseType() == DatabaseType.POSTGRESQL) {
            sql = """
                    INSERT INTO %s
                      (id, entity_type, entity_id, connection_status, last_checked_at,
                       last_error_code, last_error_summary, state_version)
                    VALUES (?, 'PROVIDER', ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                      connection_status = EXCLUDED.connection_status,
                      last_checked_at = EXCLUDED.last_checked_at,
                      last_error_code = EXCLUDED.last_error_code,
                      last_error_summary = EXCLUDED.last_error_summary,
                      state_version = %s.state_version + 1,
                      updated_at = %s
                    """.strip().formatted(qualify(connection, "object_runtime_state"),
                            qualify(connection, "object_runtime_state"),
                            d.nowFunction());
        } else {
            sql = """
                    INSERT INTO %s
                      (id, entity_type, entity_id, connection_status, last_checked_at,
                       last_error_code, last_error_summary, state_version)
                    VALUES (?, 'PROVIDER', ?, ?, ?, ?, ?, 1)
                    ON DUPLICATE KEY UPDATE
                      connection_status = VALUES(connection_status),
                      last_checked_at = VALUES(last_checked_at),
                      last_error_code = VALUES(last_error_code),
                      last_error_summary = VALUES(last_error_summary),
                      state_version = state_version + 1,
                      updated_at = %s
                    """.strip().formatted(qualify(connection, "object_runtime_state"),
                            d.nowFunction());
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, UUID.randomUUID());
            d.bindUuid(statement, 2, providerId);
            statement.setString(3, connectionStatus);
            statement.setObject(4, checkedAt == null ? Timestamp.from(java.time.Instant.now()) : checkedAt);
            statement.setString(5, errorCode);
            statement.setString(6, errorSummary);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("运行状态写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 检测/调用结束后的 Credential 健康收敛（幂等 upsert）。 */
    public void upsertCredentialHealth(Connection connection, UUID credentialId, String healthStatus,
                                       OffsetDateTime checkedAt, String errorCode, String errorSummary) {
        DatabaseDialect d = dialect(connection);
        String sql;
        if (d.databaseType() == DatabaseType.POSTGRESQL) {
            sql = """
                    INSERT INTO %s
                      (id, entity_type, entity_id, health_status, last_checked_at,
                       last_error_code, last_error_summary, state_version)
                    VALUES (?, 'CREDENTIAL', ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                      health_status = EXCLUDED.health_status,
                      last_checked_at = EXCLUDED.last_checked_at,
                      last_error_code = EXCLUDED.last_error_code,
                      last_error_summary = EXCLUDED.last_error_summary,
                      state_version = %s.state_version + 1,
                      updated_at = %s
                    """.strip().formatted(qualify(connection, "object_runtime_state"),
                            qualify(connection, "object_runtime_state"),
                            d.nowFunction());
        } else {
            sql = """
                    INSERT INTO %s
                      (id, entity_type, entity_id, health_status, last_checked_at,
                       last_error_code, last_error_summary, state_version)
                    VALUES (?, 'CREDENTIAL', ?, ?, ?, ?, ?, 1)
                    ON DUPLICATE KEY UPDATE
                      health_status = VALUES(health_status),
                      last_checked_at = VALUES(last_checked_at),
                      last_error_code = VALUES(last_error_code),
                      last_error_summary = VALUES(last_error_summary),
                      state_version = state_version + 1,
                      updated_at = %s
                    """.strip().formatted(qualify(connection, "object_runtime_state"),
                            d.nowFunction());
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, UUID.randomUUID());
            d.bindUuid(statement, 2, credentialId);
            statement.setString(3, healthStatus);
            statement.setObject(4, checkedAt == null ? Timestamp.from(java.time.Instant.now()) : checkedAt);
            statement.setString(5, errorCode);
            statement.setString(6, errorSummary);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("凭证健康状态写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 批量读取（列表组合状态，避免 N+1）。 */
    public Map<UUID, JdbcObjectRuntimeStateRepository.RuntimeStateSnapshot> findByEntities(
            Connection connection, String entityType, Collection<UUID> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Map.of();
        }
        DatabaseDialect d = dialect(connection);
        String qualifiedTable = qualify(connection, "object_runtime_state");
        if (d.supportsArrayType()) {
            String sql = "SELECT entity_id, connection_status, health_status, last_success_at, "
                    + "last_checked_at, last_error_code FROM " + qualifiedTable
                    + " WHERE entity_type = ? AND entity_id = ANY(?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, entityType);
                statement.setArray(2, connection.createArrayOf("uuid", entityIds.toArray(UUID[]::new)));
                return executeFindByEntities(d, statement);
            } catch (SQLException e) {
                throw new IllegalStateException("运行状态批量读取失败：" + e.getClass().getSimpleName(), e);
            }
        } else {
            String sql = "SELECT entity_id, connection_status, health_status, last_success_at, "
                    + "last_checked_at, last_error_code FROM " + qualifiedTable
                    + " WHERE entity_type = ? AND entity_id IN (" + inPlaceholders(entityIds.size()) + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int idx = 1;
                statement.setString(idx++, entityType);
                for (UUID id : entityIds) {
                    d.bindUuid(statement, idx++, id);
                }
                return executeFindByEntities(d, statement);
            } catch (SQLException e) {
                throw new IllegalStateException("运行状态批量读取失败：" + e.getClass().getSimpleName(), e);
            }
        }
    }

    private Map<UUID, JdbcObjectRuntimeStateRepository.RuntimeStateSnapshot> executeFindByEntities(
            DatabaseDialect d, PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            Map<UUID, JdbcObjectRuntimeStateRepository.RuntimeStateSnapshot> states = new HashMap<>();
            while (rs.next()) {
                states.put(d.readUuid(rs, "entity_id"),
                        new JdbcObjectRuntimeStateRepository.RuntimeStateSnapshot(
                                rs.getString("connection_status"),
                                rs.getString("health_status"),
                                d.readOffsetDateTime(rs, "last_success_at"),
                                d.readOffsetDateTime(rs, "last_checked_at"),
                                rs.getString("last_error_code")));
            }
            return Map.copyOf(states);
        }
    }
}

