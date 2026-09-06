package com.lightai.storage.check;

import com.lightai.client.json.ProtocolJson;
import com.lightai.client.provider.UsageSummary;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * provider_check_record JDBC 仓储（DATABASE_PLAN §12）。
 * 检测记录为不可变事实：只插入，不更新；最近记录按 created_at desc。
 */
public class JdbcProviderCheckRecordRepository {

    private static final String COLUMNS =
            "id, target_type, target_id, mode, status, operator_id, trace_id, attempt_id, "
                    + "started_at, ended_at, total_ms, usage, provider_request_id, error_code, error_summary";

    private final String schemaName;

    public JdbcProviderCheckRecordRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcProviderCheckRecordRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, CheckRecordRow row) {
        String sql = "INSERT INTO %s.provider_check_record (%s) VALUES %s"
                .formatted(qualified(), COLUMNS, placeholders(COLUMNS));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, row.id());
            statement.setString(2, row.targetType());
            statement.setObject(3, row.targetId());
            statement.setString(4, row.mode());
            statement.setString(5, row.status());
            statement.setString(6, row.operatorId());
            statement.setString(7, row.traceId());
            statement.setObject(8, row.attemptId() == null ? null : UUID.fromString(row.attemptId()));
            statement.setObject(9, row.startedAt());
            statement.setObject(10, row.endedAt());
            statement.setInt(11, row.totalMs());
            statement.setString(12, row.usage() == null ? null
                    : ProtocolJson.protocol().writeValueAsString(row.usage()));
            statement.setString(13, row.providerRequestId());
            statement.setString(14, row.errorCode());
            statement.setString(15, row.errorSummary());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("检测记录写入失败：" + e.getClass().getSimpleName(), e);
        } catch (Exception e) {
            throw new IllegalStateException("检测记录序列化失败", e);
        }
    }

    /** 最近检测记录：详情页 recent_check_records（C-024），按 created_at 倒序。 */
    public List<CheckRecordRow> findLatestByTarget(Connection connection, String targetType,
                                                   UUID targetId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE target_type = ? AND target_id = ? ORDER BY created_at DESC, id DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetType);
            statement.setObject(2, targetId);
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<CheckRecordRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检测记录查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 批量最近检测记录（列表组合，DISTINCT ON 每目标取最新，避免 N+1）。 */
    public List<CheckRecordRow> findLatestByTargets(Connection connection, String targetType,
                                                    java.util.Collection<UUID> targetIds) {
        if (targetIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT DISTINCT ON (target_id) " + COLUMNS + " FROM " + qualified()
                + " WHERE target_type = ? AND target_id = ANY(?) ORDER BY target_id, created_at DESC, id DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetType);
            statement.setArray(2, connection.createArrayOf("uuid", targetIds.toArray(UUID[]::new)));
            try (ResultSet rs = statement.executeQuery()) {
                List<CheckRecordRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检测记录批量查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 发布校验 CONNECTION_CHECK_STALE 数据源：近期是否有成功检测（BE-039）。 */
    public boolean existsSuccessSince(Connection connection, String targetType, UUID targetId,
                                      java.time.OffsetDateTime since) {
        String sql = "SELECT 1 FROM " + qualified()
                + " WHERE target_type = ? AND target_id = ? AND status = 'SUCCEEDED' "
                + "AND created_at >= ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetType);
            statement.setObject(2, targetId);
            statement.setObject(3, since);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("近期成功检测查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private CheckRecordRow mapRow(ResultSet rs) throws SQLException {
        String usageJson = rs.getString("usage");
        UsageSummary usage = null;
        if (usageJson != null && !usageJson.isBlank()) {
            try {
                usage = ProtocolJson.protocol().readValue(usageJson, UsageSummary.class);
            } catch (Exception ignored) {
                usage = null;
            }
        }
        UUID attemptId = rs.getObject("attempt_id", UUID.class);
        return new CheckRecordRow(
                rs.getObject("id", UUID.class),
                rs.getString("target_type"),
                rs.getObject("target_id", UUID.class),
                rs.getString("mode"),
                rs.getString("status"),
                rs.getString("operator_id"),
                rs.getString("trace_id"),
                attemptId == null ? null : attemptId.toString(),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getInt("total_ms"),
                usage,
                rs.getString("provider_request_id"),
                rs.getString("error_code"),
                rs.getString("error_summary"));
    }

    private String qualified() {
        return schemaName + ".provider_check_record";
    }

    private static String placeholders(String columns) {
        int count = columns.split(",").length;
        return "(" + "?,".repeat(count - 1) + "?)";
    }
}
