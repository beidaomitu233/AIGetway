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

    public List<QueueEntryRow> queueEntries(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, alias_id, sequence, blocking_policy_ids, estimated_tokens, "
                + "status, enqueued_at, deadline_at, acquired_at, ended_at, wake_reason, error_code "
                + "FROM " + qualify(connection, "queue_entry") + " WHERE trace_id = ? ORDER BY sequence ASC";
        return query(connection, sql, traceId, (rs, dl) -> new QueueEntryRow(
                dl.readUuid(rs, "id"),
                rs.getString("trace_id"),
                dl.readUuid(rs, "alias_id"),
                rs.getLong("sequence"),
                fromJsonList(rs.getString("blocking_policy_ids")),
                rs.getLong("estimated_tokens"),
                rs.getString("status"),
                dl.readOffsetDateTime(rs, "enqueued_at"),
                dl.readOffsetDateTime(rs, "deadline_at"),
                dl.readOffsetDateTime(rs, "acquired_at"),
                dl.readOffsetDateTime(rs, "ended_at"),
                rs.getString("wake_reason"),
                rs.getString("error_code")));
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
        StringBuilder itemSql = new StringBuilder("SELECT id, reservation_id, scope_id, scope_type, "
                + "policy_ids FROM ").append(qualify(connection, "capacity_reservation_item"))
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

    public List<RecoveryDecisionRow> recoveryDecisions(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, sequence, source_attempt_id, action, reason_code, "
                + "scheduled_delay_ms, target_route_candidate_id, target_credential_id, retries_used, "
                + "credential_failovers_used, fallbacks_used, remaining_timeout_ms, created_at FROM "
                + qualify(connection, "recovery_decision") + " WHERE trace_id = ? ORDER BY sequence ASC";
        return query(connection, sql, traceId, (rs, dl) -> new RecoveryDecisionRow(
                dl.readUuid(rs, "id"),
                rs.getString("trace_id"),
                rs.getInt("sequence"),
                dl.readUuid(rs, "source_attempt_id"),
                rs.getString("action"),
                rs.getString("reason_code"),
                rs.getInt("scheduled_delay_ms"),
                dl.readUuid(rs, "target_route_candidate_id"),
                dl.readUuid(rs, "target_credential_id"),
                rs.getInt("retries_used"),
                rs.getInt("credential_failovers_used"),
                rs.getInt("fallbacks_used"),
                rs.getInt("remaining_timeout_ms"),
                dl.readOffsetDateTime(rs, "created_at")));
    }

    /** 仅读取 trigger_trace_id 等于本 Trace 的事件（FE-027）。 */
    public List<CircuitEventRow> circuitEvents(Connection connection, String traceId) {
        String sql = "SELECT id, circuit_id, from_state, to_state, trigger_type, error_code, reason, "
                + "occurred_at FROM " + qualify(connection, "circuit_event") + " "
                + "WHERE trigger_trace_id = ? ORDER BY occurred_at ASC, created_at ASC";
        return query(connection, sql, traceId, (rs, dl) -> new CircuitEventRow(
                dl.readUuid(rs, "id"),
                dl.readUuid(rs, "circuit_id"),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("trigger_type"),
                rs.getString("error_code"),
                rs.getString("reason"),
                dl.readOffsetDateTime(rs, "occurred_at")));
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
                                                         java.util.Collection<UUID> credentialIds) {
        if (credentialIds == null || credentialIds.isEmpty()) {
            return Map.of();
        }
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT credential_id, masked_value FROM ")
                .append(qualify(connection, "credential_secret")).append(" WHERE credential_id IN (")
                .append(inPlaceholders(credentialIds.size()))
                .append(")");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (UUID id : credentialIds) {
                d.bindUuid(statement, index++, id);
            }
            try (ResultSet rs = statement.executeQuery()) {
                Map<UUID, String> masks = new HashMap<>();
                while (rs.next()) {
                    masks.put(d.readUuid(rs, "credential_id"), rs.getString("masked_value"));
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
