package com.lightai.storage.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lightai.client.json.ProtocolJson;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.trace.ObservationRows.CircuitEventRow;
import com.lightai.storage.trace.ObservationRows.ContentSampleRow;
import com.lightai.storage.trace.ObservationRows.QueueEntryRow;
import com.lightai.storage.trace.ObservationRows.RecoveryDecisionRow;
import com.lightai.storage.trace.ObservationRows.ReservationItemRow;
import com.lightai.storage.trace.ObservationRows.ReservationRow;
import com.lightai.storage.trace.ObservationRows.RouteDecisionRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trace 详情子表读取（DATABASE_PLAN 第 17—22/24 表，BE-032）。
 * 详情按 trace_id 批量读取；任何下级读取失败由调用方收敛为
 * OBSERVATION_DATA_UNAVAILABLE，不返回局部详情。
 */
public class JdbcTraceDetailRepository extends AbstractJdbcRepository {

    public JdbcTraceDetailRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcTraceDetailRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcTraceDetailRepository() {
        super();
    }

    public List<RouteDecisionRow> routeDecisions(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, sequence, route_candidate_id, decision, reason_code, "
                + "reason_detail, observed_status, created_at FROM " + qualify(connection, "route_decision")
                + " WHERE trace_id = ? ORDER BY sequence ASC";
        return query(connection, sql, traceId, (rs, dl) -> new RouteDecisionRow(
                dl.readUuid(rs, "id"),
                rs.getString("trace_id"),
                rs.getInt("sequence"),
                dl.readUuid(rs, "route_candidate_id"),
                rs.getString("decision"),
                rs.getString("reason_code"),
                rs.getString("reason_detail"),
                rs.getString("observed_status"),
                dl.readOffsetDateTime(rs, "created_at")));
    }

    /**
     * 队列读取：当前已发布迁移的 queue_entry 只有 queue_position/expires_at/dequeued_at
     * 等旧列（BE-P23-001），alias/blocking/estimate/wake 等扩展字段暂以空值映射，
     * 待 DB-222 迁移补齐后恢复完整读取；运行时尚未持久化队列条目，空集合属正常状态。
     */
    public List<QueueEntryRow> queueEntries(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, queue_position, status, enqueued_at, expires_at, "
                + "dequeued_at FROM " + qualify(connection, "queue_entry")
                + " WHERE trace_id = ? ORDER BY queue_position ASC";
        return query(connection, sql, traceId, (rs, dl) -> new QueueEntryRow(
                dl.readUuid(rs, "id"),
                rs.getString("trace_id"),
                null,
                rs.getLong("queue_position"),
                List.of(),
                0L,
                rs.getString("status"),
                dl.readOffsetDateTime(rs, "enqueued_at"),
                dl.readOffsetDateTime(rs, "expires_at"),
                null,
                dl.readOffsetDateTime(rs, "dequeued_at"),
                null,
                null));
    }

    /** 预占与其 item 的 policy_ids 一次装配。 */
    public List<ReservationWithItems> reservations(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, attempt_id, status, reserved_tokens, actual_tokens, "
                + "created_at, settled_at, release_reason FROM " + qualify(connection, "capacity_reservation")
                + " WHERE trace_id = ? ORDER BY created_at ASC, id ASC";
        List<ReservationWithItems> result = new ArrayList<>();
        List<ReservationRow> rows = query(connection, sql, traceId, (rs, dl) -> new ReservationRow(
                dl.readUuid(rs, "id"),
                rs.getString("trace_id"),
                dl.readUuid(rs, "attempt_id"),
                rs.getString("status"),
                rs.getLong("reserved_tokens"),
                getLongOrNull(rs, "actual_tokens"),
                dl.readOffsetDateTime(rs, "created_at"),
                dl.readOffsetDateTime(rs, "settled_at"),
                rs.getString("release_reason")));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<String>> policyIds = new HashMap<>();
        // 已发布迁移的 capacity_reservation_item 无 policy_ids 列（BE-P23-001），
        // 暂按空集合装配，待 DB-222 迁移补齐后恢复策略链路。
        StringBuilder itemSql = new StringBuilder("SELECT id, reservation_id, scope_id, scope_type, "
                + "'[]' AS policy_ids FROM ").append(qualify(connection, "capacity_reservation_item"))
                .append(" WHERE reservation_id IN (");
        List<Object> params = new ArrayList<>();
        for (ReservationRow row : rows) {
            if (params.size() > 0) {
                itemSql.append(", ");
            }
            itemSql.append("?");
            params.add(row.id());
        }
        itemSql.append(")");
        List<ReservationItemRow> items = queryList(connection, itemSql.toString(), params, (rs, dl) -> {
            UUID reservationId = dl.readUuid(rs, "reservation_id");
            List<String> policies = fromJsonList(rs.getString("policy_ids"));
            policyIds.computeIfAbsent(reservationId, k -> new ArrayList<>()).addAll(policies);
            return new ReservationItemRow(dl.readUuid(rs, "id"), reservationId,
                    dl.readUuid(rs, "scope_id"), rs.getString("scope_type"), policies);
        });
        for (ReservationRow row : rows) {
            result.add(new ReservationWithItems(row, policyIds.getOrDefault(row.id(), List.of())));
        }
        return List.copyOf(result);
    }

    public record ReservationWithItems(ReservationRow reservation, List<String> policyIds) {
    }

    /**
     * 恢复决策读取：当前已发布迁移只有 failed_attempt_sequence/decision_type/
     * target_candidate_id/target_credential_id 列（BE-P23-001），先按现映射返回，
     * action 取 decision_type，关联 Attempt 与恢复计数等扩展列待 DB-222 迁移补齐；
     * 运行时尚未持久化恢复决策，历史与现网数据为空集合属正常状态。
     */
    public List<RecoveryDecisionRow> recoveryDecisions(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, sequence, failed_attempt_sequence, decision_type, "
                + "reason_code, target_candidate_id, target_credential_id, created_at FROM "
                + qualify(connection, "recovery_decision") + " WHERE trace_id = ? ORDER BY sequence ASC";
        return query(connection, sql, traceId, (rs, dl) -> new RecoveryDecisionRow(
                dl.readUuid(rs, "id"),
                rs.getString("trace_id"),
                rs.getInt("sequence"),
                null,
                rs.getString("decision_type"),
                rs.getString("reason_code"),
                0,
                dl.readUuid(rs, "target_candidate_id"),
                dl.readUuid(rs, "target_credential_id"),
                0,
                0,
                0,
                0,
                dl.readOffsetDateTime(rs, "created_at")));
    }

    /**
     * 熔断事件读取：已发布迁移的 circuit_event 无 trigger_trace_id 关联列（BE-P23-001），
     * 无法按 Trace 过滤；在 DB-222 迁移补齐关联列前返回空集合，不虚构造事件。
     */
    public List<CircuitEventRow> circuitEvents(Connection connection, String traceId) {
        return List.of();
    }

    public Optional<ContentSampleRow> contentSample(Connection connection, String traceId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT id, trace_id, sampled_messages, sampled_response, redaction_version, "
                + "expires_at FROM " + qualify(connection, "trace_content_sample") + " WHERE trace_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(new ContentSampleRow(
                        d.readUuid(rs, "id"),
                        rs.getString("trace_id"),
                        rs.getString("sampled_messages"),
                        rs.getString("sampled_response"),
                        rs.getString("redaction_version"),
                        d.readOffsetDateTime(rs, "expires_at"))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("诊断样本读取失败", e);
        }
    }

    /** 当前掩码查询（Credential 只显示名称与当前 masked_value，FE-027）；缺失凭证返回空。 */
    public Map<UUID, String> maskedValuesByCredentialIds(Connection connection,
                                                         java.util.Collection<UUID> channelCredentialIds) {
        if (channelCredentialIds == null || channelCredentialIds.isEmpty()) {
            return Map.of();
        }
        DatabaseDialect d = dialect(connection);
        // credential_secret 已在 V4 折叠进 channel_credential，掩码改从新表读取
        StringBuilder sql = new StringBuilder("SELECT id, masked_value FROM ")
                .append(qualify(connection, "channel_credential")).append(" WHERE id IN (")
                .append(inPlaceholders(channelCredentialIds.size()))
                .append(")");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (UUID id : channelCredentialIds) {
                d.bindUuid(statement, index++, id);
            }
            try (ResultSet rs = statement.executeQuery()) {
                Map<UUID, String> masks = new HashMap<>();
                while (rs.next()) {
                    masks.put(d.readUuid(rs, "id"), rs.getString("masked_value"));
                }
                return Map.copyOf(masks);
            }
        } catch (SQLException e) {
            throw translate("凭证掩码读取失败", e);
        }
    }

    private interface RowMapper<T> {
        T map(ResultSet rs, DatabaseDialect d) throws SQLException;
    }

    private <T> List<T> query(Connection connection, String sql, String traceId,
                              RowMapper<T> mapper) {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs, d));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("Trace详情读取失败", e);
        }
    }

    private <T> List<T> queryList(Connection connection, String sql, List<Object> params,
                                  RowMapper<T> mapper) {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, params, d);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs, d));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("Trace详情读取失败", e);
        }
    }

    private static List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return ProtocolJson.protocol().readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("policy_ids 解析失败", e);
        }
    }
}
