package com.lightai.storage.upstream;

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
 * model_sync_job / model_sync_item JDBC 仓储（DATABASE_PLAN DB-213，V5 新增）。
 * 作业与明细由调用方在同一事务写入；(channel_id, idempotency_key) 唯一索引保证
 * 同渠道幂等键仅生成一次预览，重复请求读取原作业实现幂等重放。
 * 支持 PostgreSQL 与 MySQL 8.0 / H2(MySQL 模式) 双方言自适应。
 */
public class JdbcModelSyncJobRepository extends AbstractJdbcRepository {

    private static final String JOB_COLUMNS =
            "id, channel_id, status, idempotency_key, requested_by, preview_expires_at, "
                    + "summary_json, committed_at, error_code, created_at, updated_at";

    public JdbcModelSyncJobRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcModelSyncJobRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcModelSyncJobRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insertJob(Connection connection, ModelSyncJobRecord job) {
        DatabaseDialect d = dialect(connection);
        String nowFn = d.nowFunction();
        String sql = "INSERT INTO " + qualify(connection, "model_sync_job")
                + " (id, channel_id, status, idempotency_key, requested_by, preview_expires_at, "
                + "summary_json, committed_at, error_code, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, " + d.jsonPlaceholder() + ", ?, ?, " + nowFn + ", " + nowFn + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, job.id());
            d.bindUuid(statement, 2, job.channelId());
            statement.setString(3, job.status());
            statement.setString(4, job.idempotencyKey());
            statement.setString(5, job.requestedBy());
            statement.setObject(6, job.previewExpiresAt());
            d.bindJson(statement, 7, job.summaryJson() == null ? "{}" : job.summaryJson());
            statement.setObject(8, job.committedAt());
            statement.setString(9, job.errorCode());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("模型同步任务写入失败", e);
        }
    }

    public void insertItem(Connection connection, ModelSyncItemRecord item) {
        DatabaseDialect d = dialect(connection);
        String nowFn = d.nowFunction();
        String sql = "INSERT INTO " + qualify(connection, "model_sync_item")
                + " (id, job_id, upstream_model_id, model_id, change_type, before_json, after_json, "
                + "conflict_fields, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, " + d.jsonPlaceholder() + ", " + d.jsonPlaceholder() + ", "
                + d.jsonPlaceholder() + ", " + nowFn + ", " + nowFn + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, item.id());
            d.bindUuid(statement, 2, item.jobId());
            d.bindUuid(statement, 3, item.upstreamModelId());
            statement.setString(4, item.modelId());
            statement.setString(5, item.changeType());
            d.bindJson(statement, 6, item.beforeJson());
            d.bindJson(statement, 7, item.afterJson());
            d.bindJson(statement, 8, item.conflictFields());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("模型同步明细写入失败", e);
        }
    }

    public Optional<ModelSyncJobRecord> findJobById(Connection connection, UUID jobId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + JOB_COLUMNS + " FROM " + qualify(connection, "model_sync_job")
                + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapJob(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("模型同步任务读取失败", e);
        }
    }

    /** 幂等重放入口：同渠道同幂等键的既有作业（任意状态）。 */
    public Optional<ModelSyncJobRecord> findByChannelAndIdempotencyKey(
            Connection connection, UUID channelId, String idempotencyKey) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + JOB_COLUMNS + " FROM " + qualify(connection, "model_sync_job")
                + " WHERE channel_id = ? AND idempotency_key = ? ORDER BY created_at ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, channelId);
            statement.setString(2, idempotencyKey);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapJob(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("模型同步任务幂等查询失败", e);
        }
    }

    public List<ModelSyncItemRecord> findItemsByJob(Connection connection, UUID jobId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT id, job_id, upstream_model_id, model_id, change_type, before_json, "
                + "after_json, conflict_fields FROM " + qualify(connection, "model_sync_item")
                + " WHERE job_id = ? ORDER BY model_id ASC, id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                List<ModelSyncItemRecord> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(mapItem(rs, d));
                }
                return List.copyOf(items);
            }
        } catch (SQLException e) {
            throw translate("模型同步明细查询失败", e);
        }
    }

    /** 状态收敛：COMMITTED 记录提交时间，FAILED/DISCARDED/EXPIRED 记录错误码。 */
    public void transitionJob(Connection connection, UUID jobId, String status, String errorCode) {
        DatabaseDialect d = dialect(connection);
        String committedAt = "COMMITTED".equals(status) ? d.nowFunction() : "NULL";
        String sql = "UPDATE " + qualify(connection, "model_sync_job")
                + " SET status = ?, error_code = ?, committed_at = " + committedAt
                + ", updated_at = " + d.nowFunction() + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, errorCode);
            d.bindUuid(statement, 3, jobId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("模型同步任务状态更新失败", e);
        }
    }

    private ModelSyncJobRecord mapJob(ResultSet rs, DatabaseDialect d) throws SQLException {
        OffsetDateTime committedAt = d.readOffsetDateTime(rs, "committed_at");
        return new ModelSyncJobRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "channel_id"),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getString("requested_by"),
                d.readOffsetDateTime(rs, "preview_expires_at"),
                d.readJson(rs, "summary_json"),
                committedAt,
                rs.getString("error_code"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }

    private ModelSyncItemRecord mapItem(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new ModelSyncItemRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "job_id"),
                d.readUuid(rs, "upstream_model_id"),
                rs.getString("model_id"),
                rs.getString("change_type"),
                d.readJson(rs, "before_json"),
                d.readJson(rs, "after_json"),
                d.readJson(rs, "conflict_fields"));
    }
}
