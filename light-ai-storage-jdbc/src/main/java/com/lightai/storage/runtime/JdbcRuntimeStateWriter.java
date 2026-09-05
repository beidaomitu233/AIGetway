package com.lightai.storage.runtime;

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
public class JdbcRuntimeStateWriter {

    private final String schemaName;

    public JdbcRuntimeStateWriter(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcRuntimeStateWriter() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** 检测/调用结束后的 Provider 状态收敛（幂等 upsert）。 */
    public void upsertProviderState(Connection connection, UUID providerId, String connectionStatus,
                                    OffsetDateTime checkedAt, String errorCode, String errorSummary) {
        String sql = """
                INSERT INTO %s.object_runtime_state
                  (id, entity_type, entity_id, connection_status, last_checked_at,
                   last_error_code, last_error_summary, state_version)
                VALUES (?, 'PROVIDER', ?, ?, ?, ?, ?, 1)
                ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                  connection_status = EXCLUDED.connection_status,
                  last_checked_at = EXCLUDED.last_checked_at,
                  last_error_code = EXCLUDED.last_error_code,
                  last_error_summary = EXCLUDED.last_error_summary,
                  state_version = object_runtime_state.state_version + 1,
                  updated_at = now()
                """.strip().formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, providerId);
            statement.setString(3, connectionStatus);
            statement.setObject(4, checkedAt == null ? Timestamp.from(java.time.Instant.now()) : checkedAt);
            statement.setString(5, errorCode);
            statement.setString(6, errorSummary);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("运行状态写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 批量读取（列表组合状态，避免 N+1）。 */
    public Map<UUID, JdbcObjectRuntimeStateRepository.RuntimeStateSnapshot> findByEntities(
            Connection connection, String entityType, Collection<UUID> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT entity_id, connection_status, health_status, last_success_at, "
                + "last_checked_at, last_error_code FROM " + qualified()
                + " WHERE entity_type = ? AND entity_id = ANY(?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityType);
            statement.setArray(2, connection.createArrayOf("uuid", entityIds.toArray(UUID[]::new)));
            try (ResultSet rs = statement.executeQuery()) {
                Map<UUID, JdbcObjectRuntimeStateRepository.RuntimeStateSnapshot> states = new HashMap<>();
                while (rs.next()) {
                    states.put(rs.getObject("entity_id", UUID.class),
                            new JdbcObjectRuntimeStateRepository.RuntimeStateSnapshot(
                                    rs.getString("connection_status"),
                                    rs.getString("health_status"),
                                    rs.getObject("last_success_at", OffsetDateTime.class),
                                    rs.getObject("last_checked_at", OffsetDateTime.class),
                                    rs.getString("last_error_code")));
                }
                return Map.copyOf(states);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("运行状态批量读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private String qualified() {
        return schemaName + ".object_runtime_state";
    }
}
