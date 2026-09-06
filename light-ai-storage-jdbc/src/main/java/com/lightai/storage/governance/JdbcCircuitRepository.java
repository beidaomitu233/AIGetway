package com.lightai.storage.governance;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.dialect.DatabaseType;

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

/**
 * circuit_state / circuit_event / circuit_command JDBC 仓储（DATABASE_PLAN §23/24/25）。
 * 运行态数据不入配置草稿；人工命令 C-013：PENDING 落库 → 共享存储 CAS 应用 →
 * 事件与命令终态同事务落库。event_key 幂等去重。
 * 支持 PostgreSQL 与 MySQL 5.7 / 8.0 双方言自适应。
 */
public class JdbcCircuitRepository extends AbstractJdbcRepository {

    public JdbcCircuitRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcCircuitRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcCircuitRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** 人工命令受理（PENDING），与受理审计同事务由服务层定义。 */
    public void insertCommand(Connection connection, UUID commandId, UUID circuitId,
                              String requestId, String action, long expectedStateVersion,
                              String reason, Integer openSeconds, String operatorId) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "circuit_command") + " (id, request_id, circuit_id, action, "
                + "expected_state_version, reason, open_seconds, operator_id, status, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, commandId);
            statement.setString(2, requestId);
            d.bindUuid(statement, 3, circuitId);
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
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "circuit_command") + " SET status = ?, error_code = ?, "
                + "applied_at = COALESCE(applied_at, " + d.nowFunction() + "), completed_at = " + d.nowFunction()
                + ", updated_at = " + d.nowFunction() + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, errorCode);
            d.bindUuid(statement, 3, commandId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("熔断命令终态写入失败", e);
        }
    }

    public Optional<CommandRow> findCommandById(Connection connection, UUID commandId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT id, request_id, circuit_id, action, expected_state_version, reason, "
                + "open_seconds, operator_id, status, error_code FROM "
                + qualify(connection, "circuit_command") + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, commandId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CommandRow(
                        d.readUuid(rs, "id"),
                        rs.getString("request_id"),
                        d.readUuid(rs, "circuit_id"),
                        rs.getString("action"),
                        rs.getLong("expected_state_version"),
                        rs.getString("reason"),
                        getIntOrNull(rs, "open_seconds"),
                        rs.getString("operator_id"),
                        rs.getString("status"),
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
        DatabaseDialect d = dialect(connection);
        String sql = d.insertIgnoreSql(qualify(connection, "circuit_event"),
                "id, event_key, circuit_id, from_state, to_state, trigger_type, command_id, error_code, reason, occurred_at",
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?",
                "event_key");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, UUID.randomUUID());
            statement.setString(2, eventKey);
            d.bindUuid(statement, 3, circuitId);
            statement.setString(4, fromState);
            statement.setString(5, toState);
            statement.setString(6, triggerType);
            d.bindUuid(statement, 7, commandId);
            statement.setString(8, errorCode);
            statement.setString(9, reason);
            statement.setTimestamp(10, occurredAt == null ? null : Timestamp.from(occurredAt.toInstant()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("熔断事件写入失败", e);
        }
    }

    /** 运行状态行同步（upsert，state_version 随共享存储推进）。 */
    public void upsertState(Connection connection, UUID circuitId, UUID providerModelId,
                            UUID credentialId, String state, long stateVersion,
                            String policySnapshotJson, String openSource, String reason) {
        DatabaseDialect d = dialect(connection);
        String sql;
        if (d.databaseType() == DatabaseType.MYSQL) {
            sql = "INSERT INTO " + qualify(connection, "circuit_state")
                    + " (id, provider_model_id, credential_id, state, state_version, policy_snapshot, open_source, last_reason, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + d.nowFunction() + ", " + d.nowFunction() + ") "
                    + "ON DUPLICATE KEY UPDATE "
                    + "state = VALUES(state), state_version = VALUES(state_version), "
                    + "policy_snapshot = VALUES(policy_snapshot), open_source = VALUES(open_source), "
                    + "last_reason = VALUES(last_reason), updated_at = " + d.nowFunction();
        } else {
            sql = "INSERT INTO " + qualify(connection, "circuit_state")
                    + " (id, provider_model_id, credential_id, state, state_version, policy_snapshot, open_source, last_reason, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, now(), now()) "
                    + "ON CONFLICT (provider_model_id, credential_id) DO UPDATE SET "
                    + "state = EXCLUDED.state, state_version = EXCLUDED.state_version, "
                    + "policy_snapshot = EXCLUDED.policy_snapshot, open_source = EXCLUDED.open_source, "
                    + "last_reason = EXCLUDED.last_reason, updated_at = now()";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, circuitId);
            d.bindUuid(statement, 2, providerModelId);
            d.bindUuid(statement, 3, credentialId);
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
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT id, provider_model_id, credential_id, state, "
                + "state_version, policy_snapshot, open_source, last_reason, updated_at FROM ")
                .append(qualify(connection, "circuit_state"));
        List<Object> params = new ArrayList<>();
        if (state != null && !state.isBlank()) {
            sql.append(" WHERE state = ?");
            params.add(state.strip());
        }
        sql.append(" ORDER BY CASE state WHEN 'OPEN' THEN 0 WHEN 'HALF_OPEN' THEN 1 ELSE 2 END, "
                + "updated_at DESC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<StateRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new StateRow(
                            d.readUuid(rs, "id"),
                            d.readUuid(rs, "provider_model_id"),
                            d.readUuid(rs, "credential_id"),
                            rs.getString("state"),
                            rs.getLong("state_version"),
                            rs.getString("policy_snapshot"),
                            rs.getString("open_source"),
                            rs.getString("last_reason"),
                            d.readOffsetDateTime(rs, "updated_at")));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("熔断状态查询失败", e);
        }
    }

    public long countStates(Connection connection, String state) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualify(connection, "circuit_state"));
        List<Object> params = new ArrayList<>();
        if (state != null && !state.isBlank()) {
            sql.append(" WHERE state = ?");
            params.add(state.strip());
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
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
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT id, event_key, circuit_id, from_state, "
                + "to_state, trigger_type, command_id, error_code, reason, occurred_at FROM ")
                .append(qualify(connection, "circuit_event")).append(" WHERE circuit_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(circuitId);
        if (triggerType != null && !triggerType.isBlank()) {
            sql.append(" AND trigger_type = ?");
            params.add(triggerType.strip());
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<EventRow> rows = new ArrayList<>();
                while (rs.next()) {
                    UUID cmdId = d.readUuid(rs, "command_id");
                    rows.add(new EventRow(
                            d.readUuid(rs, "id"),
                            rs.getString("event_key"),
                            d.readUuid(rs, "circuit_id"),
                            rs.getString("from_state"),
                            rs.getString("to_state"),
                            rs.getString("trigger_type"),
                            cmdId == null ? null : cmdId.toString(),
                            rs.getString("error_code"),
                            rs.getString("reason"),
                            d.readOffsetDateTime(rs, "occurred_at")));
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
}
