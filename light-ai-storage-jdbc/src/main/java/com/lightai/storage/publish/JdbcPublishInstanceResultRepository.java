package com.lightai.storage.publish;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
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
public final class JdbcPublishInstanceResultRepository extends AbstractJdbcRepository implements PublishInstanceResultRepository {

    public JdbcPublishInstanceResultRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcPublishInstanceResultRepository() {
        super();
    }

    /** 固定目标集合批量创建 PENDING 行（发布准备事务内）。 */
    @Override
    public void insertPending(Connection connection, UUID publishId, long fromSnapshotNo,
                              long targetSnapshotNo, List<UUID> instanceIds) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "publish_instance_result")
                + " (id, publish_id, instance_id, from_snapshot_no, target_snapshot_no, status, retry_count) "
                + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (UUID instanceId : instanceIds) {
                d.bindUuid(statement, 1, UUID.randomUUID());
                d.bindUuid(statement, 2, publishId);
                d.bindUuid(statement, 3, instanceId);
                statement.setLong(4, fromSnapshotNo);
                statement.setLong(5, targetSnapshotNo);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw translate("发布实例结果写入失败", e);
        }
    }

    /** 实例上报状态更新：旧报告拒绝由服务层先读水位判定。 */
    @Override
    public void applyReport(Connection connection, UUID publishId, UUID instanceId, String status,
                            OffsetDateTime reportedAt, int retryCount, Long loadDurationMs,
                            String errorCode, String errorSummary) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "publish_instance_result")
                + " SET status = ?, reported_at = ?, retry_count = ?, load_duration_ms = ?, "
                + "error_code = ?, error_summary = ?, updated_at = " + d.nowFunction() + " "
                + "WHERE publish_id = ? AND instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setObject(2, reportedAt);
            statement.setInt(3, retryCount);
            statement.setObject(4, loadDurationMs);
            statement.setString(5, errorCode);
            statement.setString(6, errorSummary);
            d.bindUuid(statement, 7, publishId);
            d.bindUuid(statement, 8, instanceId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("实例上报更新失败", e);
        }
    }

    @Override
    public Optional<PublishInstanceResultRecord> find(Connection connection, UUID publishId,
                                                      UUID instanceId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT r.*, i.runtime_mode, i.runtime_version, i.supported_schema_versions, "
                + "i.loaded_adapter_types FROM " + qualify(connection, "publish_instance_result") + " r "
                + "LEFT JOIN " + qualify(connection, "runtime_instance") + " i ON i.instance_id = r.instance_id "
                + "WHERE r.publish_id = ? AND r.instance_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, publishId);
            d.bindUuid(statement, 2, instanceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(d, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("实例结果读取失败", e);
        }
    }

    /** 发布实例进度（联出能力字段，供 PublishRecordDetail）。 */
    @Override
    public List<PublishInstanceResultRecord> listByPublish(Connection connection, UUID publishId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT r.*, i.runtime_mode, i.runtime_version, i.supported_schema_versions, "
                + "i.loaded_adapter_types FROM " + qualify(connection, "publish_instance_result") + " r "
                + "LEFT JOIN " + qualify(connection, "runtime_instance") + " i ON i.instance_id = r.instance_id "
                + "WHERE r.publish_id = ? ORDER BY i.instance_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, publishId);
            try (ResultSet rs = statement.executeQuery()) {
                List<PublishInstanceResultRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(d, rs));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("实例结果列表失败", e);
        }
    }

    /** 批量状态迁移（激活指令下发、准备超时标记 TIMED_OUT）。 */
    @Override
    public void markAllStatus(Connection connection, UUID publishId, String status) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "publish_instance_result")
                + " SET status = ?, updated_at = " + d.nowFunction() + " WHERE publish_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            d.bindUuid(statement, 2, publishId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("实例结果批量更新失败", e);
        }
    }

    private PublishInstanceResultRecord mapRow(DatabaseDialect d, ResultSet rs) throws SQLException {
        return new PublishInstanceResultRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "publish_id"),
                d.readUuid(rs, "instance_id"),
                rs.getLong("from_snapshot_no"),
                rs.getLong("target_snapshot_no"),
                rs.getString("status"),
                rs.getInt("retry_count"),
                getLongOrNull(rs, "load_duration_ms"),
                d.readOffsetDateTime(rs, "reported_at"),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                d.readOffsetDateTime(rs, "updated_at"),
                rs.getString("runtime_mode"),
                rs.getString("runtime_version"),
                StringListJson.parse(rs.getString("supported_schema_versions")),
                StringListJson.parse(rs.getString("loaded_adapter_types")));
    }
}

