package com.lightai.storage.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * object_runtime_state 读取仓储（DATABASE_PLAN §11）。
 * 运行状态由检测/外部调用即时更新，与配置草稿相互独立；
 * BE-P02 仅读取，写入由检测与运行内核（BE-P04/P05）负责。
 */
public final class JdbcObjectRuntimeStateRepository {

    private static final String COLUMNS =
            "connection_status, health_status, last_success_at, last_checked_at, last_error_code";

    private final String schemaName;

    public JdbcObjectRuntimeStateRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcObjectRuntimeStateRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public Optional<RuntimeStateSnapshot> findByEntity(Connection connection, String entityType, UUID entityId) {
        String sql = "SELECT " + COLUMNS + " FROM " + schemaName
                + ".object_runtime_state WHERE entity_type = ? AND entity_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entityType);
            statement.setObject(2, entityId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new RuntimeStateSnapshot(
                        rs.getString("connection_status"),
                        rs.getString("health_status"),
                        rs.getObject("last_success_at", OffsetDateTime.class),
                        rs.getObject("last_checked_at", OffsetDateTime.class),
                        rs.getString("last_error_code")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("运行状态读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 运行状态摘要（只读投影）。 */
    public record RuntimeStateSnapshot(
            String connectionStatus,
            String healthStatus,
            OffsetDateTime lastSuccessAt,
            OffsetDateTime lastCheckedAt,
            String lastErrorCode) {

        public String connectionStatusOrDefault() {
            return connectionStatus == null ? "UNKNOWN" : connectionStatus;
        }
    }
}
