package com.lightai.storage.check;

import com.lightai.client.json.ProtocolJson;
import com.lightai.client.provider.UsageSummary;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * provider_check_record JDBC 仓储（DATABASE_PLAN §12）。
 * 检测记录为不可变事实：只插入，不更新；最近记录按 created_at desc。
 */
public class JdbcProviderCheckRecordRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, target_type, target_id, mode, status, operator_id, trace_id, attempt_id, "
                    + "started_at, ended_at, total_ms, usage, provider_request_id, error_code, error_summary";

    public JdbcProviderCheckRecordRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcProviderCheckRecordRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcProviderCheckRecordRepository() {
        super();
    }

    public void insert(Connection connection, CheckRecordRow row) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "provider_check_record") + " (" + COLUMNS + ") VALUES "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + d.jsonPlaceholder() + ", ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, row.id());
            statement.setString(2, row.targetType());
            d.bindUuid(statement, 3, row.targetId());
            statement.setString(4, row.mode());
            statement.setString(5, row.status());
            statement.setString(6, row.operatorId());
            statement.setString(7, row.traceId());
            d.bindUuid(statement, 8, row.attemptId() == null ? null : UUID.fromString(row.attemptId()));
            statement.setObject(9, row.startedAt());
            statement.setObject(10, row.endedAt());
            statement.setInt(11, row.totalMs());
            d.bindJson(statement, 12, row.usage() == null ? null
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
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "provider_check_record")
                + " WHERE target_type = ? AND target_id = ? ORDER BY created_at DESC, id DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetType);
            d.bindUuid(statement, 2, targetId);
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<CheckRecordRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(d, rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检测记录查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 批量最近检测记录（列表组合，每目标取最新，避免 N+1）。 */
    public List<CheckRecordRow> findLatestByTargets(Connection connection, String targetType,
                                                    java.util.Collection<UUID> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return List.of();
        }
        DatabaseDialect d = dialect(connection);
        if (d.supportsArrayType()) {
            String sql = "SELECT DISTINCT ON (target_id) " + COLUMNS + " FROM " + qualify(connection, "provider_check_record")
                    + " WHERE target_type = ? AND target_id = ANY(?) ORDER BY target_id, created_at DESC, id DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, targetType);
                statement.setArray(2, connection.createArrayOf("uuid", targetIds.toArray(UUID[]::new)));
                try (ResultSet rs = statement.executeQuery()) {
                    List<CheckRecordRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(mapRow(d, rs));
                    }
                    return List.copyOf(rows);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("检测记录批量查询失败：" + e.getClass().getSimpleName(), e);
            }
        } else {
            String placeholders = inPlaceholders(targetIds.size());
            String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "provider_check_record")
                    + " WHERE target_type = ? AND target_id IN (" + placeholders + ")"
                    + " ORDER BY created_at DESC, id DESC";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int idx = 1;
                statement.setString(idx++, targetType);
                for (UUID id : targetIds) {
                    d.bindUuid(statement, idx++, id);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    java.util.Map<UUID, CheckRecordRow> latestByTarget = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        CheckRecordRow row = mapRow(d, rs);
                        latestByTarget.putIfAbsent(row.targetId(), row);
                    }
                    return List.copyOf(latestByTarget.values());
                }
            } catch (SQLException e) {
                throw new IllegalStateException("检测记录批量查询失败：" + e.getClass().getSimpleName(), e);
            }
        }
    }

    /** 发布校验 CONNECTION_CHECK_STALE 数据源：近期是否有成功检测（BE-039）。 */
    public boolean existsSuccessSince(Connection connection, String targetType, UUID targetId,
                                      java.time.OffsetDateTime since) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "provider_check_record")
                + " WHERE target_type = ? AND target_id = ? AND status = 'SUCCEEDED' "
                + "AND created_at >= ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetType);
            d.bindUuid(statement, 2, targetId);
            statement.setObject(3, since);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("近期成功检测查询失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private CheckRecordRow mapRow(DatabaseDialect d, ResultSet rs) throws SQLException {
        String usageJson = d.readJson(rs, "usage");
        UsageSummary usage = null;
        if (usageJson != null && !usageJson.isBlank()) {
            try {
                usage = ProtocolJson.protocol().readValue(usageJson, UsageSummary.class);
            } catch (Exception ignored) {
                usage = null;
            }
        }
        UUID attemptId = d.readUuid(rs, "attempt_id");
        return new CheckRecordRow(
                d.readUuid(rs, "id"),
                rs.getString("target_type"),
                d.readUuid(rs, "target_id"),
                rs.getString("mode"),
                rs.getString("status"),
                rs.getString("operator_id"),
                rs.getString("trace_id"),
                attemptId == null ? null : attemptId.toString(),
                d.readOffsetDateTime(rs, "started_at"),
                d.readOffsetDateTime(rs, "ended_at"),
                rs.getInt("total_ms"),
                usage,
                rs.getString("provider_request_id"),
                rs.getString("error_code"),
                rs.getString("error_summary"));
    }
}
