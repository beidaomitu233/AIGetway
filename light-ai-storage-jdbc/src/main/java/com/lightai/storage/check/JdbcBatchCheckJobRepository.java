package com.lightai.storage.check;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** batch_check_job / batch_check_item JDBC 实现（DATABASE_PLAN §13/§14）。 */
public final class JdbcBatchCheckJobRepository implements BatchCheckJobRepository {

    private static final String JOB_COLUMNS = """
            id, status, operator_id, total_count, completed_count, success_count, failure_count,
            cancelled_count, started_at, ended_at, command, created_at, updated_at""";

    private static final String ITEM_COLUMNS = """
            id, job_id, provider_model_id, sequence, status, check_record_id,
            started_at, ended_at, error_code""";

    private final String schemaName;

    public JdbcBatchCheckJobRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcBatchCheckJobRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public void insert(Connection connection, BatchCheckJobRecord job, List<BatchCheckItemRecord> items) {
        String jobSql = "INSERT INTO " + qualified() + " (" + JOB_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String itemSql = "INSERT INTO " + itemQualified() + " (" + ITEM_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement jobStatement = connection.prepareStatement(jobSql);
             PreparedStatement itemStatement = connection.prepareStatement(itemSql)) {
            int i = 1;
            jobStatement.setObject(i++, job.id());
            jobStatement.setString(i++, job.status());
            jobStatement.setString(i++, job.operatorId());
            jobStatement.setInt(i++, job.totalCount());
            jobStatement.setInt(i++, job.completedCount());
            jobStatement.setInt(i++, job.successCount());
            jobStatement.setInt(i++, job.failureCount());
            jobStatement.setInt(i++, job.cancelledCount());
            jobStatement.setTimestamp(i++, job.startedAt() == null ? null : Timestamp.from(job.startedAt().toInstant()));
            jobStatement.setTimestamp(i++, job.endedAt() == null ? null : Timestamp.from(job.endedAt().toInstant()));
            jobStatement.setString(i++, job.commandJson());
            jobStatement.setTimestamp(i++, Timestamp.from(job.createdAt().toInstant()));
            jobStatement.setTimestamp(i, Timestamp.from(job.updatedAt().toInstant()));
            jobStatement.executeUpdate();

            for (BatchCheckItemRecord item : items) {
                int j = 1;
                itemStatement.setObject(j++, item.id());
                itemStatement.setObject(j++, item.jobId());
                itemStatement.setObject(j++, item.providerModelId());
                itemStatement.setInt(j++, item.sequence());
                itemStatement.setString(j++, item.status());
                itemStatement.setObject(j++, item.checkRecordId());
                itemStatement.setTimestamp(j++, item.startedAt() == null ? null : Timestamp.from(item.startedAt().toInstant()));
                itemStatement.setTimestamp(j++, item.endedAt() == null ? null : Timestamp.from(item.endedAt().toInstant()));
                itemStatement.setString(j, item.errorCode());
                itemStatement.addBatch();
            }
            itemStatement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("batch_check 写入失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<BatchCheckJobRecord> find(Connection connection, UUID id) {
        String sql = "SELECT " + JOB_COLUMNS + " FROM " + qualified() + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapJob(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("batch_check_job 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public List<BatchCheckItemRecord> listItems(Connection connection, UUID jobId) {
        String sql = "SELECT " + ITEM_COLUMNS + " FROM " + itemQualified()
                + " WHERE job_id = ? ORDER BY sequence ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                List<BatchCheckItemRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapItem(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("batch_check_item 读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void updateSummary(Connection connection, BatchCheckJobRecord job) {
        String sql = """
                UPDATE %s SET status=?, completed_count=?, success_count=?, failure_count=?,
                cancelled_count=?, started_at=?, ended_at=?, updated_at=? WHERE id=?""".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, job.status());
            statement.setInt(i++, job.completedCount());
            statement.setInt(i++, job.successCount());
            statement.setInt(i++, job.failureCount());
            statement.setInt(i++, job.cancelledCount());
            statement.setTimestamp(i++, job.startedAt() == null ? null : Timestamp.from(job.startedAt().toInstant()));
            statement.setTimestamp(i++, job.endedAt() == null ? null : Timestamp.from(job.endedAt().toInstant()));
            statement.setTimestamp(i++, Timestamp.from(OffsetDateTime.now().toInstant()));
            statement.setObject(i, job.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("batch_check_job 更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void updateItem(Connection connection, BatchCheckItemRecord item) {
        String sql = """
                UPDATE %s SET status=?, check_record_id=?, started_at=?, ended_at=?, error_code=?, updated_at=?
                WHERE id=?""".formatted(itemQualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, item.status());
            statement.setObject(i++, item.checkRecordId());
            statement.setTimestamp(i++, item.startedAt() == null ? null : Timestamp.from(item.startedAt().toInstant()));
            statement.setTimestamp(i++, item.endedAt() == null ? null : Timestamp.from(item.endedAt().toInstant()));
            statement.setString(i++, item.errorCode());
            statement.setTimestamp(i++, Timestamp.from(OffsetDateTime.now().toInstant()));
            statement.setObject(i, item.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("batch_check_item 更新失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public int cancelPendingItems(Connection connection, UUID jobId) {
        String sql = "UPDATE " + itemQualified() + """
                SET status=?, ended_at=?, updated_at=? WHERE job_id=? AND status='PENDING'""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, BatchCheckItemRecord.STATUS_CANCELLED);
            statement.setTimestamp(2, Timestamp.from(OffsetDateTime.now().toInstant()));
            statement.setTimestamp(3, Timestamp.from(OffsetDateTime.now().toInstant()));
            statement.setObject(4, jobId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("batch_check_item 取消失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static BatchCheckJobRecord mapJob(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp endedAt = rs.getTimestamp("ended_at");
        return new BatchCheckJobRecord(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("operator_id"),
                rs.getInt("total_count"),
                rs.getInt("completed_count"),
                rs.getInt("success_count"),
                rs.getInt("failure_count"),
                rs.getInt("cancelled_count"),
                startedAt == null ? null : offset(startedAt),
                endedAt == null ? null : offset(endedAt),
                rs.getString("command"),
                offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at")));
    }

    private static BatchCheckItemRecord mapItem(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp endedAt = rs.getTimestamp("ended_at");
        return new BatchCheckItemRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getObject("provider_model_id", UUID.class),
                rs.getInt("sequence"),
                rs.getString("status"),
                rs.getObject("check_record_id", UUID.class),
                startedAt == null ? null : offset(startedAt),
                endedAt == null ? null : offset(endedAt),
                rs.getString("error_code"));
    }

    private String qualified() {
        return schemaName + ".batch_check_job";
    }

    private String itemQualified() {
        return schemaName + ".batch_check_item";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }
}
