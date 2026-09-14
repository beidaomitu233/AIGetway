package com.lightai.storage.risk;

import com.lightai.client.json.ProtocolJson;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P3 风险控制策略、版本快照、白名单与命中事件 JDBC 访问。 */
public final class JdbcRiskControlRepository extends AbstractJdbcRepository {
    public JdbcRiskControlRepository() { super(); }
    public JdbcRiskControlRepository(String schemaName) { super(schemaName); }

    public RiskPolicyRecord findPolicy(Connection connection) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT id,version,enabled,keyword_action,anomaly_window_seconds,"
                + "anomaly_request_threshold,anomaly_token_threshold,anomaly_amount_threshold,"
                + "anomaly_block_seconds,whitelist_mode,created_at,updated_at FROM "
                + qualify(connection, "risk_policy") + " ORDER BY version DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) return null;
            UUID id = dialect.readUuid(rs, "id");
            return new RiskPolicyRecord(id, rs.getLong("version"), rs.getBoolean("enabled"),
                    rs.getString("keyword_action"), rs.getInt("anomaly_window_seconds"),
                    getLongOrNull(rs, "anomaly_request_threshold"), getLongOrNull(rs, "anomaly_token_threshold"),
                    rs.getBigDecimal("anomaly_amount_threshold"), rs.getInt("anomaly_block_seconds"),
                    rs.getString("whitelist_mode"), dialect.readOffsetDateTime(rs, "created_at"),
                    dialect.readOffsetDateTime(rs, "updated_at"), keywords(connection, id),
                    whitelist(connection, id, dialect));
        } catch (SQLException e) {
            throw translate("风险策略读取失败", e);
        }
    }

    private List<RiskKeywordRuleRecord> keywords(Connection connection, UUID policyId) throws SQLException {
        DatabaseDialect dialect = dialect(connection);
        List<RiskKeywordRuleRecord> result = new ArrayList<>();
        String sql = "SELECT id,keyword,match_type,ignore_case,application_id,action,enabled FROM "
                + qualify(connection, "risk_keyword_rule") + " WHERE policy_id=? ORDER BY keyword,id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, policyId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new RiskKeywordRuleRecord(dialect.readUuid(rs, "id"), rs.getString("keyword"),
                            rs.getString("match_type"), rs.getBoolean("ignore_case"),
                            readUuidNullable(rs, "application_id", dialect), rs.getString("action"),
                            rs.getBoolean("enabled")));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<UUID> whitelist(Connection connection, UUID policyId, DatabaseDialect dialect) throws SQLException {
        List<UUID> result = new ArrayList<>();
        String sql = "SELECT application_id FROM " + qualify(connection, "risk_application_whitelist")
                + " WHERE policy_id=? ORDER BY application_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, policyId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(dialect.readUuid(rs, "application_id"));
            }
        }
        return List.copyOf(result);
    }

    /** 替换当前策略并写入不可变版本快照；调用方负责事务边界与版本 CAS。 */
    public void replace(Connection connection, RiskPolicyRecord spec,
                        List<RiskKeywordRuleRecord> rules, List<UUID> applications) {
        DatabaseDialect dialect = dialect(connection);
        try {
            deleteAll(connection, "risk_keyword_rule");
            deleteAll(connection, "risk_application_whitelist");
            deleteAll(connection, "risk_policy");
            insertPolicy(connection, spec, dialect);
            insertRules(connection, spec.id(), rules, dialect);
            insertWhitelist(connection, spec.id(), applications, dialect);
            insertRevision(connection, spec, rules, applications, dialect);
        } catch (SQLException e) {
            throw translate("风险策略写入失败", e);
        }
    }

    private void deleteAll(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + qualify(connection, table))) {
            statement.executeUpdate();
        }
    }

    private void insertPolicy(Connection connection, RiskPolicyRecord spec, DatabaseDialect dialect) throws SQLException {
        String sql = "INSERT INTO " + qualify(connection, "risk_policy")
                + " (id,created_at,updated_at,version,enabled,keyword_action,anomaly_window_seconds,"
                + "anomaly_request_threshold,anomaly_token_threshold,anomaly_amount_threshold,"
                + "anomaly_block_seconds,whitelist_mode) VALUES (? ," + dialect.nowFunction() + ","
                + dialect.nowFunction() + ",?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, spec.id());
            statement.setLong(2, spec.version());
            statement.setBoolean(3, spec.enabled());
            statement.setString(4, spec.keywordAction());
            statement.setInt(5, spec.anomalyWindowSeconds());
            setLongOrNull(statement, 6, spec.anomalyRequestThreshold());
            setLongOrNull(statement, 7, spec.anomalyTokenThreshold());
            setDecimalOrNull(statement, 8, spec.anomalyAmountThreshold());
            statement.setInt(9, spec.anomalyBlockSeconds());
            statement.setString(10, spec.whitelistMode());
            statement.executeUpdate();
        }
    }

    private void insertRules(Connection connection, UUID policyId, List<RiskKeywordRuleRecord> rules,
                             DatabaseDialect dialect) throws SQLException {
        String sql = "INSERT INTO " + qualify(connection, "risk_keyword_rule")
                + " (id,created_at,updated_at,policy_id,keyword,match_type,ignore_case,application_id,action,enabled)"
                + " VALUES (? ," + dialect.nowFunction() + "," + dialect.nowFunction() + ",?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (RiskKeywordRuleRecord rule : rules == null ? List.<RiskKeywordRuleRecord>of() : rules) {
                dialect.bindUuid(statement, 1, rule.id());
                dialect.bindUuid(statement, 2, policyId);
                statement.setString(3, rule.keyword());
                statement.setString(4, rule.matchType());
                statement.setBoolean(5, rule.ignoreCase());
                if (rule.applicationId() == null) statement.setNull(6, Types.VARCHAR);
                else dialect.bindUuid(statement, 6, rule.applicationId());
                statement.setString(7, rule.action());
                statement.setBoolean(8, rule.enabled());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertWhitelist(Connection connection, UUID policyId, List<UUID> applications,
                                 DatabaseDialect dialect) throws SQLException {
        String sql = "INSERT INTO " + qualify(connection, "risk_application_whitelist")
                + " (policy_id,application_id,created_at) VALUES (?, ?, " + dialect.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (UUID applicationId : applications == null ? List.<UUID>of() : applications) {
                dialect.bindUuid(statement, 1, policyId);
                dialect.bindUuid(statement, 2, applicationId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertRevision(Connection connection, RiskPolicyRecord spec, List<RiskKeywordRuleRecord> rules,
                                List<UUID> applications, DatabaseDialect dialect) throws SQLException {
        String sql = "INSERT INTO " + qualify(connection, "risk_policy_revision")
                + " (id,created_at,version,enabled,keyword_action,anomaly_window_seconds,"
                + "anomaly_request_threshold,anomaly_token_threshold,anomaly_amount_threshold,"
                + "anomaly_block_seconds,whitelist_mode,content_json) VALUES (? ," + dialect.nowFunction()
                + ",?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, spec.id());
            statement.setLong(2, spec.version());
            statement.setBoolean(3, spec.enabled());
            statement.setString(4, spec.keywordAction());
            statement.setInt(5, spec.anomalyWindowSeconds());
            setLongOrNull(statement, 6, spec.anomalyRequestThreshold());
            setLongOrNull(statement, 7, spec.anomalyTokenThreshold());
            setDecimalOrNull(statement, 8, spec.anomalyAmountThreshold());
            statement.setInt(9, spec.anomalyBlockSeconds());
            statement.setString(10, spec.whitelistMode());
            statement.setString(11, revisionJson(spec, rules, applications));
            statement.executeUpdate();
        }
    }

    private static String revisionJson(RiskPolicyRecord spec, List<RiskKeywordRuleRecord> rules,
                                       List<UUID> applications) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", spec.version());
        payload.put("enabled", spec.enabled());
        payload.put("keyword_action", spec.keywordAction());
        payload.put("anomaly_window_seconds", spec.anomalyWindowSeconds());
        payload.put("anomaly_request_threshold", spec.anomalyRequestThreshold());
        payload.put("anomaly_token_threshold", spec.anomalyTokenThreshold());
        payload.put("anomaly_amount_threshold", spec.anomalyAmountThreshold());
        payload.put("anomaly_block_seconds", spec.anomalyBlockSeconds());
        payload.put("whitelist_mode", spec.whitelistMode());
        payload.put("keywords", (rules == null ? List.<RiskKeywordRuleRecord>of() : rules).stream()
                .map(rule -> Map.of("id", rule.id().toString(), "keyword", rule.keyword(),
                        "match_type", rule.matchType(), "ignore_case", rule.ignoreCase(),
                        "application_id", rule.applicationId() == null ? "" : rule.applicationId().toString(),
                        "action", rule.action(), "enabled", rule.enabled())).toList());
        payload.put("whitelist_application_ids", (applications == null ? List.<UUID>of() : applications).stream()
                .map(UUID::toString).toList());
        try {
            return ProtocolJson.protocol().writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("风险策略版本快照序列化失败", e);
        }
    }

    public void insertEvent(Connection connection, RiskEventRecord event) {
        DatabaseDialect dialect = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "risk_event")
                + " (id,created_at,application_id,request_id,event_type,action,rule_id,reason) VALUES (? ,"
                + dialect.nowFunction() + ",?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, event.id());
            if (event.applicationId() == null) statement.setNull(2, Types.VARCHAR);
            else dialect.bindUuid(statement, 2, event.applicationId());
            statement.setString(3, event.requestId());
            statement.setString(4, event.eventType());
            statement.setString(5, event.action());
            if (event.ruleId() == null) statement.setNull(6, Types.VARCHAR);
            else dialect.bindUuid(statement, 6, event.ruleId());
            statement.setString(7, event.reason());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("风险事件写入失败", e);
        }
    }

    public List<RiskEventRecord> listEvents(Connection connection, int limit) {
        return listEvents(connection, limit, null, null, null, null);
    }

    public List<RiskEventRecord> listEvents(Connection connection, int limit, UUID applicationId,
                                            String eventType, Instant from, Instant to) {
        DatabaseDialect dialect = dialect(connection);
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        List<Object> values = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id,created_at,application_id,request_id,event_type,action,rule_id,reason FROM ")
                .append(qualify(connection, "risk_event")).append(" WHERE 1=1");
        if (applicationId != null) { sql.append(" AND application_id=?"); values.add(applicationId); }
        if (eventType != null && !eventType.isBlank()) { sql.append(" AND event_type=?"); values.add(eventType); }
        if (from != null) { sql.append(" AND created_at>=?"); values.add(from); }
        if (to != null) { sql.append(" AND created_at<?"); values.add(to); }
        sql.append(" ORDER BY created_at DESC,id DESC ").append(dialect.limitOffsetClause(boundedLimit, 0));
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i) instanceof UUID id) dialect.bindUuid(statement, i + 1, id);
                else statement.setObject(i + 1, values.get(i));
            }
            List<RiskEventRecord> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(new RiskEventRecord(dialect.readUuid(rs, "id"),
                        dialect.readOffsetDateTime(rs, "created_at"), readUuidNullable(rs, "application_id", dialect),
                        rs.getString("request_id"), rs.getString("event_type"), rs.getString("action"),
                        readUuidNullable(rs, "rule_id", dialect), rs.getString("reason")));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw translate("风险事件读取失败", e);
        }
    }

    private static void setLongOrNull(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }
    private static void setDecimalOrNull(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) statement.setNull(index, Types.DECIMAL); else statement.setBigDecimal(index, value);
    }
    private static UUID readUuidNullable(ResultSet rs, String column, DatabaseDialect dialect) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : (value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()));
    }
}
