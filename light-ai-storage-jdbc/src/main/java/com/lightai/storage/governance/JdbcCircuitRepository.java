package com.lightai.storage.governance;

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
 * circuit_state / circuit_event / circuit_command JDBC 仓储（DATABASE_PLAN §23/24/25）。
 * 运行态数据不入配置草稿；人工命令 C-013：PENDING 落库 → 共享存储 CAS 应用 →
 * 事件与命令终态同事务落库。event_key 幂等去重。
 */
public class JdbcCircuitRepository {

    private final String schemaName;

    public JdbcCircuitRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcCircuitRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** 人工命令受理（PENDING），与受理审计同事务由服务层定义。 */
    public void insertCommand(Connection connection, UUID commandId, UUID circuitId,
                              String requestId, String action, long expectedStateVersion,
                              String reason, Integer openSeconds, String operatorId) {
        String sql = "INSERT INTO %s.circuit_command (id, request_id, circuit_id, action, "
                + "expected_state_version, reason, open_seconds, operator_id, status, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', now(), now())"
                .formatted(qualified("circuit_command"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, commandId);
            statement.setString(2, requestId);
            statement.setObject(3, circuitId);
            statement.setString(4, action);
            statement.setLong(5, expectedStateVersion);
            statement.setString(6, reason);
            statement.setObject(7, openSeconds);
            statement.setString(8, operatorId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("熔断命令写入失败", e);
        }
    }

    /** 命令终态（APPLIED/SUCCEEDED/FAILED），事件落库同事务。 */
    public void completeCommand(Connection connection, UUID commandId, String status,
                                String errorCode) {
        String sql = "UPDATE %s.circuit_command SET status = ?, error_code = ?, "
                + "applied_at = COALESCE(applied_at, now()), completed_at = now(), updated_at = now() "
                + "WHERE id = ?".formatted(qualified("circuit_command"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, errorCode);
            statement.setObject(3, commandId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("熔断命令终态写入失败", e);
        }
    }

    public Optional<CommandRow> findCommandById(Connection connection, UUID commandId) {
        String sql = "SELECT id, request_id, circuit_id, action, expected_state_version, reason, "
                + "open_seconds, operator_id, status, error_code FROM "
                + qualified("circuit_command") + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, commandId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CommandRow(rs.getObject("id", UUID.class),
                        rs.getString("request_id"), rs.getObject("circuit_id", UUID.class),
                        rs.getString("action"), rs.getLong("expected_state_version"),
                        rs.getString("reason"), (Integer) rs.getObject("open_seconds"),
                        rs.getString("operator_id"), rs.getString("status"),
                        rs.getString("error_code")));
            }
        } catch (SQLException e) {
            throw translate("熔断命令读取失败", e);
        }
    }

    /** 状态迁移事件（event_key 幂等：冲突忽略）。 */
    public void insertEvent(Connection connection, String eventKey, UUID circuitId,
                            String fromState, String toState, String triggerType,
                            UUID commandId, String errorCode, String reason, OffsetDateTime occurredAt) {
        String sql = "INSERT INTO %s.circuit_event (id, event_key, circuit_id, from_state, "
                + "to_state, trigger_type, command_id, error_code, reason, occurred_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_key) DO NOTHING"
                .formatted(qualified("circuit_event"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, eventKey);
            statement.setObject(3, circuitId);
            statement.setString(4, fromState);
            statement.setString(5, toState);
            statement.setString(6, triggerType);
            statement.setObject(7, commandId);
            statement.setString(8, errorCode);
            statement.setString(9, reason);
            statement.setObject(10, occurredAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("熔断事件写入失败", e);
        }
    }

    /** 运行状态行同步（upsert，state_version 随共享存储推进）。 */
    public void upsertState(Connection connection, UUID circuitId, UUID providerModelId,
                            UUID credentialId, String state, long stateVersion,
                            String policySnapshotJson, String openSource, String reason) {
        String sql = "INSERT INTO %s.circuit_state (id, provider_model_id, credential_id, state, "
                + "state_version, policy_snapshot, open_source, last_reason, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, now(), now()) "
                + "ON CONFLICT (provider_model_id, credential_id) DO UPDATE SET "
                + "state = EXCLUDED.state, state_version = EXCLUDED.state_version, "
                + "policy_snapshot = EXCLUDED.policy_snapshot, open_source = EXCLUDED.open_source, "
                + "last_reason = EXCLUDED.last_reason, updated_at = now()"
                .formatted(qualified("circuit_state"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, circuitId);
            statement.setObject(2, providerModelId);
            statement.setObject(3, credentialId);
            statement.setString(4, state);
            statement.setLong(5, stateVersion);
            statement.setString(6, policySnapshotJson);
            statement.setString(7, openSource);
            statement.setString(8, reason);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("熔断状态同步失败", e);
        }
    }

    public List<StateRow> listStates(Connection connection, String state, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT id, provider_model_id, credential_id, state, "
                + "state_version, policy_snapshot, open_source, last_reason, updated_at FROM ")
                .append(qualified("circuit_state"));
        List<Object> params = new ArrayList<>();
        if (state != null && !state.isBlank()) {
            sql.append(" WHERE state = ?");
            params.add(state.strip());
        }
        sql.append(" ORDER BY CASE state WHEN 'OPEN' THEN 0 WHEN 'HALF_OPEN' THEN 1 ELSE 2 END, "
                + "updated_at DESC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<StateRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new StateRow(rs.getObject("id", UUID.class),
                            rs.getObject("provider_model_id", UUID.class),
                            rs.getObject("credential_id", UUID.class),
                            rs.getString("state"), rs.getLong("state_version"),
                            rs.getString("policy_snapshot"), rs.getString("open_source"),
                            rs.getString("last_reason"),
                            rs.getObject("updated_at", OffsetDateTime.class)));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("熔断状态查询失败", e);
        }
    }

    public long countStates(Connection connection, String state) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualified("circuit_state"));
        List<Object> params = new ArrayList<>();
        if (state != null && !state.isBlank()) {
            sql.append(" WHERE state = ?");
            params.add(state.strip());
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("熔断状态计数失败", e);
        }
    }

    public List<EventRow> listEvents(Connection connection, UUID circuitId, String triggerType,
                                     int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT id, event_key, circuit_id, from_state, "
                + "to_state, trigger_type, command_id, error_code, reason, occurred_at FROM ")
                .append(qualified("circuit_event")).append(" WHERE circuit_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(circuitId);
        if (triggerType != null && !triggerType.isBlank()) {
            sql.append(" AND trigger_type = ?");
            params.add(triggerType.strip());
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<EventRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new EventRow(rs.getObject("id", UUID.class),
                            rs.getString("event_key"), rs.getObject("circuit_id", UUID.class),
                            rs.getString("from_state"), rs.getString("to_state"),
                            rs.getString("trigger_type"),
                            rs.getObject("command_id", UUID.class) == null ? null
                                    : rs.getObject("command_id", UUID.class).toString(),
                            rs.getString("error_code"), rs.getString("reason"),
                            rs.getObject("occurred_at", OffsetDateTime.class)));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("熔断事件查询失败", e);
        }
    }

    public record CommandRow(UUID id, String requestId, UUID circuitId, String action,
                             long expectedStateVersion, String reason, Integer openSeconds,
                             String operatorId, String status, String errorCode) {
    }

    public record StateRow(UUID id, UUID providerModelId, UUID credentialId, String state,
                           long stateVersion, String policySnapshot, String openSource,
                           String lastReason, OffsetDateTime updatedAt) {
    }

    public record EventRow(UUID id, String eventKey, UUID circuitId, String fromState,
                           String toState, String triggerType, String commandId,
                           String errorCode, String reason, OffsetDateTime occurredAt) {
    }

    private String qualified(String table) {
        return schemaName + "." + table;
    }

    protected static IllegalStateException translate(String message, SQLException e) {
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
