package com.lightai.storage.cleanup;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 留存物理清理 JDBC 实现（DATABASE_PLAN 留存策略 / BE-048）。
 */
public final class JdbcRetentionDeletionRepository extends AbstractJdbcRepository implements RetentionDeletionRepository {

    public JdbcRetentionDeletionRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcRetentionDeletionRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcRetentionDeletionRepository() {
        super();
    }

    @Override
    public List<String> findExpiredTraceIds(Connection connection, OffsetDateTime cutoff, int limit) {
        String sql = "SELECT trace_id FROM " + qualify(connection, "trace")
                + " WHERE started_at < ? AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'STREAM_INTERRUPTED')"
                + " ORDER BY started_at ASC LIMIT ?";
        List<String> traceIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff.toInstant()));
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    traceIds.add(rs.getString("trace_id"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询过期 Trace 失败：" + e.getClass().getSimpleName(), e);
        }
        return traceIds;
    }

    @Override
    public int deleteTraceDetails(Connection connection, List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) {
            return 0;
        }
        int totalDeleted = 0;
        String inPlaceholders = String.join(",", Collections.nCopies(traceIds.size(), "?"));
        String[] detailTables = {"attempt", "route_decision", "recovery_decision", "capacity_reservation"};
        for (String table : detailTables) {
            String sql = "DELETE FROM " + qualify(connection, table) + " WHERE trace_id IN (" + inPlaceholders + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < traceIds.size(); i++) {
                    statement.setString(i + 1, traceIds.get(i));
                }
                totalDeleted += statement.executeUpdate();
            } catch (SQLException ignored) {
            }
        }
        return totalDeleted;
    }

    @Override
    public int deleteTraces(Connection connection, List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) {
            return 0;
        }
        String inPlaceholders = String.join(",", Collections.nCopies(traceIds.size(), "?"));
        String sql = "DELETE FROM " + qualify(connection, "trace") + " WHERE trace_id IN (" + inPlaceholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < traceIds.size(); i++) {
                statement.setString(i + 1, traceIds.get(i));
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除 Trace 失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long deleteExpiredAudit(Connection connection, OffsetDateTime cutoff) {
        String sql = "DELETE FROM " + qualify(connection, "audit_log") + " WHERE created_at < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff.toInstant()));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除过期审计日志失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long deleteExpiredSamples(Connection connection, OffsetDateTime cutoff) {
        String sql = "DELETE FROM " + qualify(connection, "trace_content_sample") + " WHERE expires_at < ? OR created_at < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff.toInstant()));
            statement.setTimestamp(2, Timestamp.from(cutoff.toInstant()));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除过期样本失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long deleteExpiredUsage(Connection connection, OffsetDateTime cutoff) {
        String sql = "DELETE FROM " + qualify(connection, "usage_aggregate") + " WHERE bucket_end < ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff.toInstant()));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除过期用量聚合失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long pendingAggregationEvents(Connection connection) {
        String sql = "SELECT COUNT(*) FROM " + qualify(connection, "usage_aggregation_event")
                + " WHERE status IN ('PENDING', 'PROCESSING')";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }
}
