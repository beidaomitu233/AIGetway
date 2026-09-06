package com.lightai.storage.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lightai.client.json.ProtocolJson;
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
public class JdbcTraceDetailRepository {

    private final String schemaName;

    public JdbcTraceDetailRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcTraceDetailRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public List<RouteDecisionRow> routeDecisions(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, sequence, route_candidate_id, decision, reason_code, "
                + "reason_detail, observed_status, created_at FROM " + schemaName
                + ".route_decision WHERE trace_id = ? ORDER BY sequence ASC";
        return query(connection, sql, traceId, rs -> new RouteDecisionRow(
                rs.getObject("id", UUID.class),
                rs.getString("trace_id"),
                rs.getInt("sequence"),
                rs.getObject("route_candidate_id", UUID.class),
                rs.getString("decision"),
                rs.getString("reason_code"),
                rs.getString("reason_detail"),
                rs.getString("observed_status"),
                rs.getObject("created_at", OffsetDateTime.class)));
    }

    public List<QueueEntryRow> queueEntries(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, alias_id, sequence, blocking_policy_ids, estimated_tokens, "
                + "status, enqueued_at, deadline_at, acquired_at, ended_at, wake_reason, error_code "
                + "FROM " + schemaName + ".queue_entry WHERE trace_id = ? ORDER BY sequence ASC";
        return query(connection, sql, traceId, rs -> new QueueEntryRow(
                rs.getObject("id", UUID.class),
                rs.getString("trace_id"),
                rs.getObject("alias_id", UUID.class),
                rs.getLong("sequence"),
                fromJsonList(rs.getString("blocking_policy_ids")),
                rs.getLong("estimated_tokens"),
                rs.getString("status"),
                rs.getObject("enqueued_at", OffsetDateTime.class),
                rs.getObject("deadline_at", OffsetDateTime.class),
                rs.getObject("acquired_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getString("wake_reason"),
                rs.getString("error_code")));
    }

    /** 预占与其 item 的 policy_ids 一次装配。 */
    public List<ReservationWithItems> reservations(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, attempt_id, status, reserved_tokens, actual_tokens, "
                + "created_at, settled_at, release_reason FROM " + schemaName
                + ".capacity_reservation WHERE trace_id = ? ORDER BY created_at ASC, id ASC";
        List<ReservationWithItems> result = new ArrayList<>();
        List<ReservationRow> rows = query(connection, sql, traceId, rs -> new ReservationRow(
                rs.getObject("id", UUID.class),
                rs.getString("trace_id"),
                rs.getObject("attempt_id", UUID.class),
                rs.getString("status"),
                rs.getLong("reserved_tokens"),
                (Long) rs.getObject("actual_tokens"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("settled_at", OffsetDateTime.class),
                rs.getString("release_reason")));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<String>> policyIds = new HashMap<>();
        StringBuilder itemSql = new StringBuilder("SELECT id, reservation_id, scope_id, scope_type, "
                + "policy_ids FROM ").append(schemaName)
                .append(".capacity_reservation_item WHERE reservation_id IN (");
        List<Object> params = new ArrayList<>();
        for (ReservationRow row : rows) {
            if (params.size() > 0) {
                itemSql.append(", ");
            }
            itemSql.append("?");
            params.add(row.id());
        }
        itemSql.append(")");
        List<ReservationItemRow> items = queryList(connection, itemSql.toString(), params, rs -> {
            UUID reservationId = rs.getObject("reservation_id", UUID.class);
            List<String> policies = fromJsonList(rs.getString("policy_ids"));
            policyIds.computeIfAbsent(reservationId, k -> new ArrayList<>()).addAll(policies);
            return new ReservationItemRow(rs.getObject("id", UUID.class), reservationId,
                    rs.getObject("scope_id", UUID.class), rs.getString("scope_type"), policies);
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
                + schemaName + ".recovery_decision WHERE trace_id = ? ORDER BY sequence ASC";
        return query(connection, sql, traceId, rs -> new RecoveryDecisionRow(
                rs.getObject("id", UUID.class),
                rs.getString("trace_id"),
                rs.getInt("sequence"),
                rs.getObject("source_attempt_id", UUID.class),
                rs.getString("action"),
                rs.getString("reason_code"),
                rs.getInt("scheduled_delay_ms"),
                rs.getObject("target_route_candidate_id", UUID.class),
                rs.getObject("target_credential_id", UUID.class),
                rs.getInt("retries_used"),
                rs.getInt("credential_failovers_used"),
                rs.getInt("fallbacks_used"),
                rs.getInt("remaining_timeout_ms"),
                rs.getObject("created_at", OffsetDateTime.class)));
    }

    /** 仅读取 trigger_trace_id 等于本 Trace 的事件（FE-027）。 */
    public List<CircuitEventRow> circuitEvents(Connection connection, String traceId) {
        String sql = "SELECT id, circuit_id, from_state, to_state, trigger_type, error_code, reason, "
                + "occurred_at FROM " + schemaName + ".circuit_event "
                + "WHERE trigger_trace_id = ? ORDER BY occurred_at ASC, created_at ASC";
        return query(connection, sql, traceId, rs -> new CircuitEventRow(
                rs.getObject("id", UUID.class),
                rs.getObject("circuit_id", UUID.class),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("trigger_type"),
                rs.getString("error_code"),
                rs.getString("reason"),
                rs.getObject("occurred_at", OffsetDateTime.class)));
    }

    public Optional<ContentSampleRow> contentSample(Connection connection, String traceId) {
        String sql = "SELECT id, trace_id, sampled_messages, sampled_response, redaction_version, "
                + "expires_at FROM " + schemaName + ".trace_content_sample WHERE trace_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(new ContentSampleRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("trace_id"),
                        rs.getString("sampled_messages"),
                        rs.getString("sampled_response"),
                        rs.getString("redaction_version"),
                        rs.getObject("expires_at", OffsetDateTime.class))) : Optional.empty();
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
        StringBuilder sql = new StringBuilder("SELECT credential_id, masked_value FROM ")
                .append(schemaName).append(".credential_secret WHERE credential_id IN (")
                .append(String.join(", ", java.util.Collections.nCopies(credentialIds.size(), "?")))
                .append(")");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (UUID id : credentialIds) {
                statement.setObject(index++, id);
            }
            try (ResultSet rs = statement.executeQuery()) {
                Map<UUID, String> masks = new HashMap<>();
                while (rs.next()) {
                    masks.put(rs.getObject("credential_id", UUID.class), rs.getString("masked_value"));
                }
                return Map.copyOf(masks);
            }
        } catch (SQLException e) {
            throw translate("凭证掩码读取失败", e);
        }
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(Connection connection, String sql, String traceId,
                              RowMapper<T> mapper) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("Trace详情读取失败", e);
        }
    }

    private <T> List<T> queryList(Connection connection, String sql, List<Object> params,
                                  RowMapper<T> mapper) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
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

    private static IllegalStateException translate(String message, SQLException e) {
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
