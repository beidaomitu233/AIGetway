package com.lightai.storage.batch;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.dialect.DatabaseType;
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
 * 批量检测任务 JDBC 仓储（DATABASE_PLAN §13/§14）。
 * 汇总列由明细更新同事务维护；取消只阻止尚未开始的项。
 */
public class JdbcBatchCheckRepository extends AbstractJdbcRepository {

    private static final String JOB_COLUMNS =
            "id, status, operator_id, total_count, completed_count, success_count, failure_count, "
                    + "cancelled_count, started_at, ended_at, command, created_at, updated_at";

    public JdbcBatchCheckRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcBatchCheckRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcBatchCheckRepository() {
        super();
    }

    public void insertJob(Connection connection, BatchJobRecord job) {
        DatabaseDialect d = dialect(connection);
        String insertColumns = JOB_COLUMNS.substring(0, JOB_COLUMNS.lastIndexOf(", created_at"));
        String nowFn = d.nowFunction();
        String sql = "INSERT INTO " + qualify(connection, "batch_check_job") + " (" + insertColumns + ", created_at, updated_at) "
                + "VALUES " + placeholders(insertColumns).replace(")", ", " + nowFn + ", " + nowFn + ")");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, job.id());
            statement.setString(2, job.status());
            statement.setString(3, job.operatorId());
            statement.setInt(4, job.totalCount());
            statement.setInt(5, job.completedCount());
            statement.setInt(6, job.successCount());
            statement.setInt(7, job.failureCount());
            statement.setInt(8, job.cancelledCount());
            statement.setObject(9, job.startedAt());
            statement.setObject(10, job.endedAt());
            statement.setString(11, job.commandJson());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("检测任务写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public void insertItem(Connection connection, BatchItemRecord item) {
        DatabaseDialect d = dialect(connection);
        String nowFn = d.nowFunction();
        String sql = "INSERT INTO " + qualify(connection, "batch_check_item")
                + " (id, job_id, provider_model_id, sequence, status, check_record_id, started_at, "
                + "ended_at, error_code, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, " + nowFn + ", " + nowFn + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, item.id());
            d.bindUuid(statement, 2, item.jobId());
            d.bindUuid(statement, 3, item.providerModelId());
            statement.setInt(4, item.sequence());
            statement.setString(5, item.status());
            d.bindUuid(statement, 6, item.checkRecordId());
            statement.setObject(7, item.startedAt());
            statement.setObject(8, item.endedAt());
            statement.setString(9, item.errorCode());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("检测明细写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public Optional<BatchJobRecord> findJobById(Connection connection, UUID jobId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + JOB_COLUMNS + " FROM " + qualify(connection, "batch_check_job") + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapJob(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检测任务读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public List<BatchItemRecord> findItemsByJob(Connection connection, UUID jobId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT id, job_id, provider_model_id, sequence, status, check_record_id, "
                + "started_at, ended_at, error_code FROM " + qualify(connection, "batch_check_item")
                + " WHERE job_id = ? ORDER BY sequence ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                List<BatchItemRecord> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(mapItem(rs, d));
                }
                return List.copyOf(items);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检测明细查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 取消所有未开始项，返回取消数量。 */
    public int cancelPendingItems(Connection connection, UUID jobId) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "batch_check_item")
                + " SET status = 'CANCELLED', updated_at = " + d.nowFunction()
                + " WHERE job_id = ? AND status = 'PENDING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, jobId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("检测明细取消失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 明细完成后同事务刷新任务汇总。 */
    public void refreshJobSummary(Connection connection, UUID jobId) {
        DatabaseDialect d = dialect(connection);
        String sql;
        String nowFn = d.nowFunction();
        if (d.databaseType() == DatabaseType.MYSQL) {
            sql = """
                    UPDATE %s job
                    JOIN (
                      SELECT count(*) AS completed,
                             COUNT(CASE WHEN status = 'SUCCEEDED' THEN 1 END) AS succeeded,
                             COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failed,
                             COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled
                        FROM %s WHERE job_id = ?
                    ) agg ON 1 = 1
                    SET
                      job.completed_count = agg.completed,
                      job.success_count = agg.succeeded,
                      job.failure_count = agg.failed,
                      job.cancelled_count = agg.cancelled,
                      job.status = CASE
                        WHEN agg.completed >= job.total_count AND agg.failed = 0 AND agg.cancelled = 0
                          THEN 'SUCCEEDED'
                        WHEN agg.completed >= job.total_count AND agg.cancelled > 0
                          THEN 'CANCELLED'
                        WHEN agg.completed >= job.total_count AND agg.succeeded = 0
                          THEN 'FAILED'
                        WHEN agg.completed >= job.total_count THEN 'PARTIAL_FAILED'
                        ELSE 'RUNNING' END,
                      job.started_at = COALESCE(job.started_at, %s),
                      job.ended_at = CASE WHEN agg.completed >= job.total_count THEN %s ELSE job.ended_at END,
                      job.updated_at = %s
                    WHERE job.id = ?
                    """.strip().formatted(
                            qualify(connection, "batch_check_job"),
                            qualify(connection, "batch_check_item"),
                            nowFn, nowFn, nowFn);
        } else {
            sql = """
                    UPDATE %s job SET
                      completed_count = agg.completed,
                      success_count = agg.succeeded,
                      failure_count = agg.failed,
                      cancelled_count = agg.cancelled,
                      status = CASE
                        WHEN agg.completed >= job.total_count AND agg.failed = 0 AND agg.cancelled = 0
                          THEN 'SUCCEEDED'
                        WHEN agg.completed >= job.total_count AND agg.cancelled > 0
                          THEN 'CANCELLED'
                        WHEN agg.completed >= job.total_count AND agg.succeeded = 0
                          THEN 'FAILED'
                        WHEN agg.completed >= job.total_count THEN 'PARTIAL_FAILED'
                        ELSE 'RUNNING' END,
                      started_at = COALESCE(job.started_at, %s),
                      ended_at = CASE WHEN agg.completed >= job.total_count THEN %s ELSE job.ended_at END,
                      updated_at = %s
                    FROM (
                      SELECT count(*) AS completed,
                             COUNT(CASE WHEN status = 'SUCCEEDED' THEN 1 END) AS succeeded,
                             COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failed,
                             COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled
                        FROM %s WHERE job_id = ?
                    ) agg
                    WHERE job.id = ?
                    """.strip().formatted(
                            qualify(connection, "batch_check_job"),
                            nowFn, nowFn, nowFn,
                            qualify(connection, "batch_check_item"));
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, jobId);
            d.bindUuid(statement, 2, jobId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("任务汇总刷新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public void updateItemStatus(Connection connection, UUID itemId, String status,
                                 UUID checkRecordId, String errorCode) {
        DatabaseDialect d = dialect(connection);
        String nowFn = d.nowFunction();
        String sql = "UPDATE " + qualify(connection, "batch_check_item")
                + " SET status = ?, check_record_id = ?, started_at = COALESCE(started_at, " + nowFn + "), "
                + "ended_at = CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED') THEN " + nowFn + " ELSE ended_at END, "
                + "error_code = ?, updated_at = " + nowFn + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            d.bindUuid(statement, 2, checkRecordId);
            statement.setString(3, status);
            statement.setString(4, errorCode);
            d.bindUuid(statement, 5, itemId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("检测明细状态更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private BatchJobRecord mapJob(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new BatchJobRecord(
                d.readUuid(rs, "id"),
                rs.getString("status"),
                rs.getString("operator_id"),
                rs.getInt("total_count"),
                rs.getInt("completed_count"),
                rs.getInt("success_count"),
                rs.getInt("failure_count"),
                rs.getInt("cancelled_count"),
                d.readOffsetDateTime(rs, "started_at"),
                d.readOffsetDateTime(rs, "ended_at"),
                rs.getString("command"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }

    private BatchItemRecord mapItem(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new BatchItemRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "job_id"),
                d.readUuid(rs, "provider_model_id"),
                rs.getInt("sequence"),
                rs.getString("status"),
                d.readUuid(rs, "check_record_id"),
                d.readOffsetDateTime(rs, "started_at"),
                d.readOffsetDateTime(rs, "ended_at"),
                rs.getString("error_code"));
    }

    private static String placeholders(String columns) {
        int count = columns.split(",").length;
        return "(" + "?,".repeat(count - 1) + "?)";
    }
}
