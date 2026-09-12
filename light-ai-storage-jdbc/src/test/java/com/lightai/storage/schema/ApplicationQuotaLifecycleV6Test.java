package com.lightai.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V6 应用域约束与回填验收（DB-201～DB-205，按「BE-P20 数据库交付契约」）。
 * 在迁移后的 H2（MySQL 模式）上验证：DB-205 键名规范转换与冲突门禁、
 * 代际密钥列、额度周期/策略历史/操作表唯一约束、context_origin 词汇约束与默认值、
 * 以及对既有策略行的周期回填语义。
 */
class ApplicationQuotaLifecycleV6Test {

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:appquota_v6_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        new DefaultSchemaMigrator(dataSource).migrate();
    }

    @Test
    void keyOperationTableEnforcesIdempotencyKey() throws Exception {
        String applicationId = uuid();
        insertKeyOperation(applicationId, "CREATE", "", "idem-1");
        assertThatThrownBy(() -> insertKeyOperation(applicationId, "CREATE", "", "idem-1"))
                .isInstanceOf(SQLException.class);
        // 同幂等键在不同操作/目标下互不冲突
        insertKeyOperation(applicationId, "CREATE", "", "idem-2");
        insertKeyOperation(applicationId, "ROTATE", uuid(), "idem-1");
    }

    @Test
    void applicationKeyExposesGenerationAndGraceColumns() throws Exception {
        String applicationId = uuid();
        execute("INSERT INTO application (id, created_at, updated_at, code, name, owner_id, "
                + "owner_name, environment, status) VALUES ('" + applicationId + "', CURRENT_TIMESTAMP, "
                + "CURRENT_TIMESTAMP, 'app-" + uuid().substring(0, 8) + "', '应用', 'owner-1', '负责人', "
                + "'PRODUCTION', 'ACTIVE')");
        String keyId = uuid();
        execute("INSERT INTO application_key (id, created_at, updated_at, application_id, name, "
                + "key_prefix, masked_value, key_digest, status, replaced_by_key_id, grace_expires_at) "
                + "VALUES ('" + keyId + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + applicationId
                + "', 'key-1', 'lai_pk_', '****', X'00112233', 'REVOKED', '" + uuid()
                + "', DATEADD('HOUR', 24, CURRENT_TIMESTAMP))");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT replaced_by_key_id, grace_expires_at "
                     + "FROM application_key WHERE id='" + keyId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNotBlank();
            assertThat(rs.getTimestamp(2)).isNotNull();
        }
    }

    @Test
    void quotaPeriodAndOperationEnforceUniquenessAndStatusVocabulary() throws Exception {
        String applicationId = uuid();
        insertPeriod(applicationId, 1, "OPEN");
        assertThatThrownBy(() -> insertPeriod(applicationId, 1, "OPEN"))
                .isInstanceOf(SQLException.class);
        insertPeriod(applicationId, 2, "CLOSING");
        assertThatThrownBy(() -> insertPeriod(applicationId, 3, "FROZEN"))
                .isInstanceOf(SQLException.class);

        execute("INSERT INTO application_quota_operation (id, created_at, updated_at, application_id, "
                + "idempotency_key, request_hash, command_json, expected_policy_version, effective_at, "
                + "status) VALUES ('" + uuid() + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '"
                + applicationId + "', 'op-1', 'hash-1', '{}', 1, CURRENT_TIMESTAMP, 'SCHEDULED')");
        assertThatThrownBy(() -> execute("INSERT INTO application_quota_operation (id, created_at, "
                + "updated_at, application_id, idempotency_key, request_hash, command_json, "
                + "expected_policy_version, effective_at, status) VALUES ('" + uuid()
                + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + applicationId + "', 'op-1', 'hash-2', "
                + "'{}', 1, CURRENT_TIMESTAMP, 'SCHEDULED')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO application_quota_operation (id, created_at, "
                + "updated_at, application_id, idempotency_key, request_hash, command_json, "
                + "expected_policy_version, effective_at, status) VALUES ('" + uuid()
                + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + applicationId + "', 'op-2', 'hash-3', "
                + "'{}', 1, CURRENT_TIMESTAMP, 'CANCELLED')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void ledgerAndReservationContextOriginDefaultAndVocabulary() throws Exception {
        execute("INSERT INTO budget_reservation (id, created_at, updated_at, request_id, "
                + "application_id, application_key_id, reserved_tokens, reserved_amount, currency, "
                + "expires_at, status) VALUES ('" + uuid() + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                + "'req-v6', '" + uuid() + "', '" + uuid() + "', 10, 1.00000000, 'USD', "
                + "DATEADD('SECOND', 60, CURRENT_TIMESTAMP), 'ACTIVE')");
        execute("INSERT INTO usage_ledger (id, created_at, event_key, application_id, input_tokens, "
                + "output_tokens, token_delta, amount_delta, currency, usage_source) VALUES ('"
                + uuid() + "', CURRENT_TIMESTAMP, 'SETTLEMENT:req-v6', '" + uuid()
                + "', 0, 0, 0, 0, 'USD', 'ACTUAL')");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT context_origin FROM budget_reservation")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("V2");
        }
        assertThatThrownBy(() -> execute("INSERT INTO usage_ledger (id, created_at, event_key, "
                + "application_id, input_tokens, output_tokens, token_delta, amount_delta, currency, "
                + "usage_source, context_origin) VALUES ('" + uuid() + "', CURRENT_TIMESTAMP, "
                + "'SETTLEMENT:req-bad', '" + uuid() + "', 0, 0, 0, 0, 'USD', 'ACTUAL', 'UNKNOWN')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void constraintKeyConversionRewritesLegacyRowOnly() throws Exception {
        String applicationId = uuid();
        execute("INSERT INTO application (id, created_at, updated_at, code, name, owner_id, "
                + "owner_name, environment, status) VALUES ('" + applicationId + "', CURRENT_TIMESTAMP, "
                + "CURRENT_TIMESTAMP, 'app-" + uuid().substring(0, 8) + "', '应用', 'owner-1', '负责人', "
                + "'PRODUCTION', 'ACTIVE')");
        execute("INSERT INTO application_model_permission (id, created_at, updated_at, application_id, "
                + "virtual_model_id, enabled, constraints_json) VALUES ('" + uuid()
                + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + applicationId + "', '" + uuid()
                + "', TRUE, '{\"max_output_tokens\":1000,\"stream_allowed\":true}')");
        String canonicalId = uuid();
        execute("INSERT INTO application_model_permission (id, created_at, updated_at, application_id, "
                + "virtual_model_id, enabled, constraints_json) VALUES ('" + canonicalId
                + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + applicationId + "', '" + uuid()
                + "', TRUE, '{\"allow_stream\":false}')");
        execute(conversionSql());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT id, constraints_json "
                     + "FROM application_model_permission ORDER BY id")) {
            boolean sawConverted = false;
            boolean sawCanonicalUntouched = false;
            while (rs.next()) {
                String json = rs.getString(2);
                assertThat(json).doesNotContain("stream_allowed");
                if (rs.getString(1).equals(canonicalId)) {
                    assertThat(json).isEqualTo("{\"allow_stream\":false}");
                    sawCanonicalUntouched = true;
                } else {
                    assertThat(json).isEqualTo("{\"max_output_tokens\":1000,\"allow_stream\":true}");
                    sawConverted = true;
                }
            }
            assertThat(sawConverted).isTrue();
            assertThat(sawCanonicalUntouched).isTrue();
        }
        // 转换语句幂等：重复执行不再改写
        execute(conversionSql());
    }

    @Test
    void constraintKeyGateStopsOnConflictingRow() throws Exception {
        String applicationId = uuid();
        execute("INSERT INTO application (id, created_at, updated_at, code, name, owner_id, "
                + "owner_name, environment, status) VALUES ('" + applicationId + "', CURRENT_TIMESTAMP, "
                + "CURRENT_TIMESTAMP, 'app-" + uuid().substring(0, 8) + "', '应用', 'owner-1', '负责人', "
                + "'PRODUCTION', 'ACTIVE')");
        execute("INSERT INTO application_model_permission (id, created_at, updated_at, application_id, "
                + "virtual_model_id, enabled, constraints_json) VALUES ('" + uuid()
                + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + applicationId + "', '" + uuid()
                + "', TRUE, '{\"allow_stream\":true,\"stream_allowed\":false}')");
        createGateTable();
        assertThatThrownBy(() -> execute(gateSql()))
                .isInstanceOf(SQLException.class);
        // 类型错误同样被门禁拦截
        execute("DELETE FROM application_model_permission WHERE constraints_json LIKE '%\"stream_allowed\":%'");
        execute("INSERT INTO application_model_permission (id, created_at, updated_at, application_id, "
                + "virtual_model_id, enabled, constraints_json) VALUES ('" + uuid()
                + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + applicationId + "', '" + uuid()
                + "', TRUE, '{\"stream_allowed\":\"true\"}')");
        createGateTable();
        assertThatThrownBy(() -> execute(gateSql()))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void backfillCreatesFirstPeriodHistoryAndCurrentPointer() throws Exception {
        String applicationId = uuid();
        execute("INSERT INTO application (id, created_at, updated_at, code, name, owner_id, "
                + "owner_name, environment, status) VALUES ('" + applicationId + "', CURRENT_TIMESTAMP, "
                + "CURRENT_TIMESTAMP, 'app-" + uuid().substring(0, 8) + "', '应用', 'owner-1', '负责人', "
                + "'PRODUCTION', 'ACTIVE')");
        execute("INSERT INTO application_quota_policy (id, created_at, updated_at, application_id, "
                + "token_limit, amount_limit, currency, rpm, tpm, period_type, tokens_used, "
                + "tokens_reserved, amount_used, amount_reserved) VALUES ('" + uuid()
                + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + applicationId + "', 100000, 50.00000000, "
                + "'USD', 60, 200000, 'MONTH', 1200, 300, 12.50000000, 2.00000000)");
        // 回填语句幂等：连续执行两次
        execute(backfillPeriodSql());
        execute(backfillPeriodSql());
        execute(backfillPointerSql());
        execute(backfillPointerSql());
        execute(backfillHistorySql());
        execute(backfillHistorySql());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT p.current_period_id, p.policy_version, "
                     + "q.period_no, q.status, q.tokens_used, q.tokens_reserved, q.amount_used, "
                     + "q.opening_tokens_used, (SELECT count(*) FROM application_quota_policy_history h "
                     + "WHERE h.application_id = p.application_id) AS history_rows "
                     + "FROM application_quota_policy p "
                     + "JOIN application_quota_period q ON q.application_id = p.application_id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNotBlank();
            assertThat(rs.getLong(2)).isEqualTo(1);
            assertThat(rs.getLong(3)).isEqualTo(1);
            assertThat(rs.getString(4)).isEqualTo("OPEN");
            assertThat(rs.getLong(5)).isEqualTo(1200);
            assertThat(rs.getLong(6)).isEqualTo(300);
            assertThat(rs.getBigDecimal(7)).isEqualByComparingTo("12.50000000");
            assertThat(rs.getLong(8)).isZero();
            assertThat(rs.getLong(9)).isEqualTo(1);
            assertThat(rs.next()).isFalse();
        }
        // 重复回填不产生第二条业务事件
        execute(backfillPeriodSql());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM application_quota_period WHERE application_id='" + applicationId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(1);
        }
    }

    // ---------- V6 语句原文（与迁移脚本保持一致，验证其在真实 schema 上的行为） ----------

    private void createGateTable() throws SQLException {
        execute("CREATE TEMPORARY TABLE IF NOT EXISTS db205_conflict_gate ("
                + "id VARCHAR(36) NOT NULL, CONSTRAINT chk_db205_no_conflict CHECK (FALSE))");
    }

    private static String gateSql() {
        return "INSERT INTO db205_conflict_gate (id) "
                + "SELECT id FROM application_model_permission "
                + "WHERE constraints_json IS NOT NULL AND ("
                + "(constraints_json LIKE '%\"stream_allowed\":%' AND constraints_json LIKE '%\"allow_stream\":%') "
                + "OR (constraints_json LIKE '%\"stream_allowed\":%' "
                + "AND constraints_json NOT LIKE '%\"stream_allowed\":true%' "
                + "AND constraints_json NOT LIKE '%\"stream_allowed\":false%') "
                + "OR (constraints_json LIKE '%\"allow_stream\":%' "
                + "AND constraints_json NOT LIKE '%\"allow_stream\":true%' "
                + "AND constraints_json NOT LIKE '%\"allow_stream\":false%'))";
    }

    private static String conversionSql() {
        return "UPDATE application_model_permission "
                + "SET constraints_json = REPLACE(constraints_json, '\"stream_allowed\"', '\"allow_stream\"') "
                + "WHERE constraints_json LIKE '%\"stream_allowed\":%' "
                + "AND constraints_json NOT LIKE '%\"allow_stream\":%'";
    }

    private static String backfillPeriodSql() {
        return "INSERT INTO application_quota_period ("
                + "id, created_at, updated_at, application_id, period_no, period_type, timezone, currency, "
                + "status, period_start, period_end, previous_period_id, "
                + "opening_tokens_used, tokens_used, tokens_reserved, "
                + "opening_amount_used, amount_used, amount_reserved) "
                + "SELECT p.application_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, p.application_id, 1, "
                + "p.period_type, COALESCE(rc.timezone, 'Asia/Shanghai'), p.currency, 'OPEN', "
                + "p.period_start, p.period_end, NULL, "
                + "0, p.tokens_used, p.tokens_reserved, 0, p.amount_used, p.amount_reserved "
                + "FROM application_quota_policy p "
                + "LEFT JOIN runtime_config rc ON rc.singleton_key = 1 "
                + "WHERE NOT EXISTS (SELECT 1 FROM application_quota_period q "
                + "WHERE q.application_id = p.application_id)";
    }

    private static String backfillPointerSql() {
        return "UPDATE application_quota_policy p "
                + "SET current_period_id = (SELECT q.id FROM application_quota_period q "
                + "WHERE q.application_id = p.application_id AND q.period_no = 1), "
                + "policy_version = 1 "
                + "WHERE (p.current_period_id IS NULL OR p.policy_version IS NULL) "
                + "AND EXISTS (SELECT 1 FROM application_quota_period q "
                + "WHERE q.application_id = p.application_id AND q.period_no = 1)";
    }

    private static String backfillHistorySql() {
        return "INSERT INTO application_quota_policy_history ("
                + "id, created_at, application_id, policy_version, period_id, policy_json, operator_id, reason) "
                + "SELECT p.application_id, CURRENT_TIMESTAMP, p.application_id, 1, p.current_period_id, "
                + "JSON_OBJECT('token_limit', p.token_limit, 'amount_limit', p.amount_limit, 'currency', p.currency, "
                + "'rpm', p.rpm, 'tpm', p.tpm, 'period_type', p.period_type, "
                + "'timezone', q.timezone, 'period_start', p.period_start, 'period_end', p.period_end), "
                + "'system-migration', 'DB-203 回填当前策略' "
                + "FROM application_quota_policy p "
                + "JOIN application_quota_period q ON q.application_id = p.application_id AND q.period_no = 1 "
                + "WHERE p.current_period_id IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM application_quota_policy_history h "
                + "WHERE h.application_id = p.application_id AND h.policy_version = 1)";
    }

    // ---------- fixtures ----------

    private void insertKeyOperation(String applicationId, String operation, String targetKey,
                                    String idempotencyKey) throws SQLException {
        execute("INSERT INTO application_key_operation (id, created_at, application_id, operation, "
                + "target_key, idempotency_key, request_hash, result_key_id, result_json) VALUES ('"
                + uuid() + "', CURRENT_TIMESTAMP, '" + applicationId + "', '" + operation + "', '"
                + targetKey + "', '" + idempotencyKey + "', 'hash', '" + uuid() + "', '{}')");
    }

    private void insertPeriod(String applicationId, long periodNo, String status) throws SQLException {
        execute("INSERT INTO application_quota_period (id, created_at, updated_at, application_id, "
                + "period_no, period_type, timezone, currency, status) VALUES ('" + uuid()
                + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '" + applicationId + "', " + periodNo
                + ", 'MONTH', 'Asia/Shanghai', 'USD', '" + status + "')");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
