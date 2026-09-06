package com.lightai.storage.trace;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * usage_aggregation_event JDBC 仓储（DATABASE_PLAN 第 26 表，BE-033）。
 * 终态 Trace 同事务写入唯一事件（U(trace_id) 冲突即忽略）；
 * 聚合器原子取得 PENDING/到期 FAILED/租约过期 PROCESSING 事件并递增 lock_generation，
 * 过期 worker 提交前必须核对 generation（fencing），旧提交被拒绝。
 */
public class JdbcUsageAggregationEventRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, trace_id, status, locked_by, locked_at, next_retry_at, completed_at, "
                    + "lock_generation, retry_count, error_code, error_summary, created_at, updated_at";

    public JdbcUsageAggregationEventRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcUsageAggregationEventRepository() {
        super();
    }

    /** 终态同事务写入唯一事件；事件已存在时幂等跳过（重放零增量前提）。 */
    public boolean insertIfAbsent(Connection connection, String traceId) {
        DatabaseDialect d = dialect(connection);
        String table = qualify(connection, "usage_aggregation_event");
        String sql = d.insertIgnoreSql(
                table,
                "id, trace_id, status, lock_generation, retry_count, created_at, updated_at",
                "?, ?, 'PENDING', 0, 0, " + d.nowFunction() + ", " + d.nowFunction(),
                "trace_id");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, UUID.randomUUID());
            statement.setString(2, traceId);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw translate("聚合事件写入失败", e);
        }
    }

    public boolean existsSucceeded(Connection connection, String traceId) {
        String sql = "SELECT 1 FROM " + qualify(connection, "usage_aggregation_event")
                + " WHERE trace_id = ? AND status = 'SUCCEEDED'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("聚合事件状态读取失败", e);
        }
    }

    /**
     * 原子取得一条可处理事件并进入 PROCESSING：递增 lock_generation 作为新租约 fencing 令牌。
     * 取得顺序：PENDING 优先，其次 next_retry_at 到期的 FAILED，再次租约超过 120 秒的 PROCESSING。
     */
    public Optional<ClaimedEvent> claimNext(Connection connection, String workerId) {
        DatabaseDialect d = dialect(connection);
        String table = qualify(connection, "usage_aggregation_event");
        if (d.supportsReturning()) {
            String sql = """
                    UPDATE %s
                       SET status = 'PROCESSING', locked_by = ?, locked_at = now(),
                           lock_generation = lock_generation + 1, updated_at = now()
                     WHERE id = (
                           SELECT id FROM %s
                            WHERE status = 'PENDING'
                               OR (status = 'FAILED' AND (next_retry_at IS NULL OR next_retry_at <= now()))
                               OR (status = 'PROCESSING' AND locked_at < now() - interval '120 seconds')
                            ORDER BY created_at ASC
                            FOR UPDATE SKIP LOCKED LIMIT 1)
                    RETURNING %s
                    """.strip().formatted(table, table, COLUMNS);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, workerId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ClaimedEvent(
                            d.readUuid(rs, "id"),
                            rs.getString("trace_id"),
                            rs.getLong("lock_generation"),
                            rs.getInt("retry_count")));
                }
            } catch (SQLException e) {
                throw translate("聚合事件取得失败", e);
            }
        } else {
            // MySQL 5.7 / 8.0：两阶段悲观行锁查询 + 更新
            String selectSql = """
                    SELECT id, trace_id, lock_generation, retry_count FROM %s
                     WHERE status = 'PENDING'
                        OR (status = 'FAILED' AND (next_retry_at IS NULL OR next_retry_at <= %s))
                        OR (status = 'PROCESSING' AND locked_at < %s)
                     ORDER BY created_at ASC
                     LIMIT 1 %s
                    """.strip().formatted(table, d.nowFunction(), d.intervalSecondsBeforeNow(120), d.forUpdateClause());
            try (PreparedStatement selStmt = connection.prepareStatement(selectSql)) {
                try (ResultSet rs = selStmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    UUID id = d.readUuid(rs, "id");
                    String traceId = rs.getString("trace_id");
                    long lockGen = rs.getLong("lock_generation");
                    int retryCount = rs.getInt("retry_count");

                    String updateSql = "UPDATE " + table
                            + " SET status = 'PROCESSING', locked_by = ?, locked_at = " + d.nowFunction()
                            + ", lock_generation = lock_generation + 1, updated_at = " + d.nowFunction()
                            + " WHERE id = ?";
                    try (PreparedStatement updStmt = connection.prepareStatement(updateSql)) {
                        updStmt.setString(1, workerId);
                        d.bindUuid(updStmt, 2, id);
                        updStmt.executeUpdate();
                    }
                    return Optional.of(new ClaimedEvent(id, traceId, lockGen + 1, retryCount));
                }
            } catch (SQLException e) {
                throw translate("聚合事件取得失败", e);
            }
        }
    }

    public record ClaimedEvent(UUID id, String traceId, long lockGeneration, int retryCount) {
    }

    /**
     * 行锁下核对 fencing 令牌与状态；事件被其他 worker 接管或已完成时返回 false，
     * 调用方放弃本次聚合提交（不回滚已计算数据，事务整体不生效）。
     */
    public boolean lockAndVerify(Connection connection, UUID eventId, long expectedGeneration) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT lock_generation, status FROM " + qualify(connection, "usage_aggregation_event")
                + " WHERE id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, eventId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getLong("lock_generation") == expectedGeneration
                        && "PROCESSING".equals(rs.getString("status"));
            }
        } catch (SQLException e) {
            throw translate("聚合事件锁定失败", e);
        }
    }

    /** HOUR/DAY 聚合与事件 SUCCEEDED 同事务提交；调用方控制事务。 */
    public void markSucceeded(Connection connection, UUID eventId) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "usage_aggregation_event")
                + " SET status = 'SUCCEEDED', completed_at = " + d.nowFunction() + ", error_code = NULL, "
                + "error_summary = NULL, next_retry_at = NULL, updated_at = " + d.nowFunction() + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, eventId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("聚合事件完成失败", e);
        }
    }

    /** 失败记录错误并按退避计划设置 next_retry_at；事件保留继续重试。 */
    public void markFailed(Connection connection, UUID eventId, String errorCode,
                           String errorSummary, OffsetDateTime nextRetryAt) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "usage_aggregation_event")
                + " SET status = 'FAILED', error_code = ?, error_summary = ?, next_retry_at = ?, "
                + "retry_count = retry_count + 1, updated_at = " + d.nowFunction() + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, errorCode);
            statement.setString(2, errorSummary);
            statement.setObject(3, nextRetryAt == null ? null : Timestamp.from(nextRetryAt.toInstant()));
            d.bindUuid(statement, 4, eventId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("聚合事件失败记录", e);
        }
    }
}

