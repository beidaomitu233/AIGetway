package com.lightai.storage.publish;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * publish_instance_result JDBC 实现（DATABASE_PLAN §34）。
 * U(publish_id, instance_id)：上报冲突由服务层按 reported_at 水位判定。
 */
public final class JdbcPublishInstanceResultRepository implements PublishInstanceResultRepository {

    private final String schemaName;

    public JdbcPublishInstanceResultRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcPublishInstanceResultRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** 固定目标集合批量创建 PENDING 行（发布准备事务内）。 */
    public void insertPending(Connection connection, UUID publishId, long fromSnapshotNo,
                              long targetSnapshotNo, List<UUID> instanceIds) {
        String sql = "INSERT INTO " + schemaName + ".publish_instance_result "
                + "(id, publish_id, instance_id, from_snapshot_no, target_snapshot_no, status, retry_count) "
                + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (UUID instanceId : instanceIds) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, publishId);
                statement.setObject(3, instanceId);
                statement.setLong(4, fromSnapshotNo);
                statement.setLong(5, targetSnapshotNo);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("发布实例结果写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 实例上报状态更新：旧报告拒绝由服务层先读水位判定。 */
    public void applyReport(Connection connection, UUID publishId, UUID instanceId, String status,
                            OffsetDateTime reportedAt, int retryCount, Long loadDurationMs,
                            String errorCode, String errorSummary) {
        String sql = "UPDATE " + schemaName + ".publish_instance_result "
                + "SET status = ?, reported_at = ?, retry_count = ?, load_duration_ms = ?, "
                + "error_code = ?, error_summary = ?, updated_at = now() "
                + "WHERE publish_id = ? AND instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setObject(2, reportedAt);
            statement.setInt(3, retryCount);
            statement.setObject(4, loadDurationMs);
            statement.setString(5, errorCode);
            statement.setString(6, errorSummary);
            statement.setObject(7, publishId);
            statement.setObject(8, instanceId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("实例上报更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public Optional<PublishInstanceResultRecord> find(Connection connection, UUID publishId,
                                                      UUID instanceId) {
        String sql = "SELECT r.*, i.runtime_mode, i.runtime_version, i.supported_schema_versions, "
                + "i.loaded_adapter_types FROM " + schemaName + ".publish_instance_result r "
                + "LEFT JOIN " + schemaName + ".runtime_instance i ON i.instance_id = r.instance_id "
                + "WHERE r.publish_id = ? AND r.instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, publishId);
            statement.setObject(2, instanceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("实例结果读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 发布实例进度（联出能力字段，供 PublishRecordDetail）。 */
    public List<PublishInstanceResultRecord> listByPublish(Connection connection, UUID publishId) {
        String sql = "SELECT r.*, i.runtime_mode, i.runtime_version, i.supported_schema_versions, "
                + "i.loaded_adapter_types FROM " + schemaName + ".publish_instance_result r "
                + "LEFT JOIN " + schemaName + ".runtime_instance i ON i.instance_id = r.instance_id "
                + "WHERE r.publish_id = ? ORDER BY i.instance_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, publishId);
            try (ResultSet rs = statement.executeQuery()) {
                List<PublishInstanceResultRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("实例结果列表失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 批量状态迁移（激活指令下发、准备超时标记 TIMED_OUT）。 */
    public void markAllStatus(Connection connection, UUID publishId, String status) {
        String sql = "UPDATE " + schemaName + ".publish_instance_result "
                + "SET status = ?, updated_at = now() WHERE publish_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setObject(2, publishId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("实例结果批量更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private PublishInstanceResultRecord mapRow(ResultSet rs) throws SQLException {
        return new PublishInstanceResultRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("publish_id", UUID.class),
                rs.getObject("instance_id", UUID.class),
                rs.getLong("from_snapshot_no"),
                rs.getLong("target_snapshot_no"),
                rs.getString("status"),
                rs.getInt("retry_count"),
                rs.getObject("load_duration_ms", Long.class),
                rs.getObject("reported_at", OffsetDateTime.class),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("runtime_mode"),
                rs.getString("runtime_version"),
                StringListJson.parse(rs.getString("supported_schema_versions")),
                StringListJson.parse(rs.getString("loaded_adapter_types")));
    }
}
