package com.lightai.storage.batch;

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
public class JdbcBatchCheckRepository {

    private static final String JOB_COLUMNS =
            "id, status, operator_id, total_count, completed_count, success_count, failure_count, "
                    + "cancelled_count, started_at, ended_at, command, created_at, updated_at";

    private final String schemaName;

    public JdbcBatchCheckRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcBatchCheckRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insertJob(Connection connection, BatchJobRecord job) {
        String insertColumns = JOB_COLUMNS.substring(0, JOB_COLUMNS.lastIndexOf(", created_at"));
        String sql = "INSERT INTO %s.batch_check_job (%s, created_at, updated_at) VALUES (%s, now(), now())"
                .formatted(qualifiedJob(), insertColumns, placeholders(insertColumns));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, job.id());
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
        String sql = "INSERT INTO %s.batch_check_item "
                + "(id, job_id, provider_model_id, sequence, status, check_record_id, started_at, "
                + "ended_at, error_code, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())".formatted(qualifiedItem());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, item.id());
            statement.setObject(2, item.jobId());
            statement.setObject(3, item.providerModelId());
            statement.setInt(4, item.sequence());
            statement.setString(5, item.status());
            statement.setObject(6, item.checkRecordId());
            statement.setObject(7, item.startedAt());
            statement.setObject(8, item.endedAt());
            statement.setString(9, item.errorCode());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("检测明细写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public Optional<BatchJobRecord> findJobById(Connection connection, UUID jobId) {
        String sql = "SELECT " + JOB_COLUMNS + " FROM " + qualifiedJob() + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapJob(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检测任务读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public List<BatchItemRecord> findItemsByJob(Connection connection, UUID jobId) {
        String sql = "SELECT id, job_id, provider_model_id, sequence, status, check_record_id, "
                + "started_at, ended_at, error_code FROM " + qualifiedItem()
                + " WHERE job_id = ? ORDER BY sequence ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                List<BatchItemRecord> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(mapItem(rs));
                }
                return List.copyOf(items);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检测明细查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 取消所有未开始项，返回取消数量。 */
    public int cancelPendingItems(Connection connection, UUID jobId) {
        String sql = "UPDATE " + qualifiedItem()
                + " SET status = 'CANCELLED', updated_at = now()"
                + " WHERE job_id = ? AND status = 'PENDING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, jobId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("检测明细取消失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 明细完成后同事务刷新任务汇总。 */
    public void refreshJobSummary(Connection connection, UUID jobId) {
        String sql = """
                UPDATE %s.batch_check_job job SET
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
                  started_at = COALESCE(job.started_at, now()),
                  ended_at = CASE WHEN agg.completed >= job.total_count THEN now() ELSE job.ended_at END,
                  updated_at = now()
                FROM (
                  SELECT count(*) AS completed,
                         count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                         count(*) FILTER (WHERE status = 'FAILED') AS failed,
                         count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled
                    FROM %s.batch_check_item WHERE job_id = ?
                ) agg
                WHERE job.id = ?
                """.strip().formatted(qualifiedJob(), schemaName);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, jobId);
            statement.setObject(2, jobId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("任务汇总刷新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    public void updateItemStatus(Connection connection, UUID itemId, String status,
                                 UUID checkRecordId, String errorCode) {
        String sql = "UPDATE " + qualifiedItem()
                + " SET status = ?, check_record_id = ?, started_at = COALESCE(started_at, now()), "
                + "ended_at = CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED') THEN now() ELSE ended_at END, "
                + "error_code = ?, updated_at = now() WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setObject(2, checkRecordId);
            statement.setString(3, status);
            statement.setString(4, errorCode);
            statement.setObject(5, itemId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("检测明细状态更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private BatchJobRecord mapJob(ResultSet rs) throws SQLException {
        return new BatchJobRecord(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("operator_id"),
                rs.getInt("total_count"),
                rs.getInt("completed_count"),
                rs.getInt("success_count"),
                rs.getInt("failure_count"),
                rs.getInt("cancelled_count"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getString("command"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private BatchItemRecord mapItem(ResultSet rs) throws SQLException {
        return new BatchItemRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getObject("provider_model_id", UUID.class),
                rs.getInt("sequence"),
                rs.getString("status"),
                rs.getObject("check_record_id", UUID.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getString("error_code"));
    }

    private static String placeholders(String columns) {
        int count = columns.split(",").length;
        return "(" + "?,".repeat(count - 1) + "?)";
    }

    private String qualifiedJob() {
        return schemaName + ".batch_check_job";
    }

    private String qualifiedItem() {
        return schemaName + ".batch_check_item";
    }
}
