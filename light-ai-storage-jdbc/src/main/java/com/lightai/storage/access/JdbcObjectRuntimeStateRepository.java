package com.lightai.storage.access;

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

/** object_runtime_state JDBC 实现（DATABASE_PLAN §11）；U(entity_type, entity_id)。 */
public final class JdbcObjectRuntimeStateRepository implements ObjectRuntimeStateRepository {

    private static final String COLUMNS = """
            entity_id, connection_status, health_status, reset_at, last_success_at,
            last_checked_at, last_failed_at, last_error_code, last_error_summary""";

    private final String schemaName;
    private final java.time.Clock clock;

    public JdbcObjectRuntimeStateRepository(String schemaName, java.time.Clock clock) {
        this.schemaName = schemaName;
        this.clock = clock;
    }

    public JdbcObjectRuntimeStateRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME, java.time.Clock.systemUTC());
    }

    @Override
    public Map<UUID, RuntimeStateRow> find(Connection connection, String entityType, Collection<UUID> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < entityIds.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
        }
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE entity_type = ? AND entity_id IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, entityType);
            for (UUID id : entityIds) {
                statement.setObject(i++, id);
            }
            try (ResultSet rs = statement.executeQuery()) {
                Map<UUID, RuntimeStateRow> rows = new HashMap<>();
                while (rs.next()) {
                    rows.put(rs.getObject("entity_id", UUID.class), mapRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("object_runtime_state 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void upsertAfterCheck(Connection connection, String entityType, UUID entityId,
                                 String connectionStatus, String healthStatus, boolean success,
                                 String errorCode, String errorSummary) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String sql = """
                INSERT INTO %s (id, entity_type, entity_id, connection_status, health_status,
                                reset_at, last_success_at, last_checked_at, last_failed_at,
                                last_error_code, last_error_summary, state_version, created_at, updated_at)
                VALUES (?,?,?,?,?,NULL,?,?,?, ?, ?, 1, ?, ?)
                ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                  connection_status = EXCLUDED.connection_status,
                  health_status = EXCLUDED.health_status,
                  last_success_at = COALESCE(EXCLUDED.last_success_at, object_runtime_state.last_success_at),
                  last_checked_at = EXCLUDED.last_checked_at,
                  last_failed_at = COALESCE(EXCLUDED.last_failed_at, object_runtime_state.last_failed_at),
                  last_error_code = EXCLUDED.last_error_code,
                  last_error_summary = EXCLUDED.last_error_summary,
                  state_version = object_runtime_state.state_version + 1,
                  updated_at = EXCLUDED.updated_at""".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setObject(i++, UUID.randomUUID());
            statement.setString(i++, entityType);
            statement.setObject(i++, entityId);
            statement.setString(i++, connectionStatus);
            statement.setString(i++, healthStatus);
            statement.setTimestamp(i++, success ? Timestamp.from(now.toInstant()) : null);
            statement.setTimestamp(i++, Timestamp.from(now.toInstant()));
            statement.setTimestamp(i++, success ? null : Timestamp.from(now.toInstant()));
            statement.setString(i++, errorCode);
            statement.setString(i++, errorSummary);
            statement.setTimestamp(i++, Timestamp.from(now.toInstant()));
            statement.setTimestamp(i, Timestamp.from(now.toInstant()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("object_runtime_state 回写失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static RuntimeStateRow mapRow(ResultSet rs) throws SQLException {
        return new RuntimeStateRow(
                rs.getString("connection_status"),
                rs.getString("health_status"),
                offset(rs.getTimestamp("reset_at")),
                offset(rs.getTimestamp("last_success_at")),
                offset(rs.getTimestamp("last_checked_at")),
                offset(rs.getTimestamp("last_failed_at")),
                rs.getString("last_error_code"),
                rs.getString("last_error_summary"));
    }

    private String qualified() {
        return schemaName + ".object_runtime_state";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }
}
