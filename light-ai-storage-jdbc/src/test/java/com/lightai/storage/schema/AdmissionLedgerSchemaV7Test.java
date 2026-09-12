package com.lightai.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V7 准入/账本/观测/留存域约束验收（DB-221～DB-225）。
 * 在迁移后的 H2（MySQL 模式）上验证：状态机词汇约束、事件账本幂等键、
 * Attempt 序号防重、聚合幂等唯一键与删除批次唯一键。
 */
class AdmissionLedgerSchemaV7Test {

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:admission_v7_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        new DefaultSchemaMigrator(dataSource).migrate();
    }

    @Test
    void budgetReservationRejectsStatusOutsideStateMachine() throws Exception {
        insertReservation("req-ok", "ACTIVE");
        // 终态守卫由仓储 WHERE status='ACTIVE' 保证，词汇约束拒绝未定义状态
        assertThatThrownBy(() -> insertReservation("req-bad", "CLOSED"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void usageLedgerDefaultsToSettleAndRejectsEventKeyReplay() throws Exception {
        insertLedger("SETTLEMENT:req-1", null);
        insertLedger("SETTLEMENT:req-2", "RELEASE");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT event_type FROM usage_ledger WHERE event_key='SETTLEMENT:req-1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("SETTLE");
        }
        assertThatThrownBy(() -> insertLedger("SETTLEMENT:req-1", null))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void attemptRejectsDuplicateSequenceWithinTrace() throws Exception {
        insertAttempt("trace-a", 1);
        insertAttempt("trace-a", 2);
        assertThatThrownBy(() -> insertAttempt("trace-a", 1))
                .isInstanceOf(SQLException.class);
        insertAttempt("trace-b", 1);
    }

    @Test
    void usageAggregateRejectsDuplicateBucketDimension() throws Exception {
        Timestamp bucket = Timestamp.from(Instant.parse("2026-09-12T08:00:00Z"));
        insertAggregate("HOUR", bucket, "k-1", "USD");
        // 同键不同币种独立分组
        insertAggregate("HOUR", bucket, "k-1", "CNY");
        insertAggregate("HOUR", bucket, "k-2", "USD");
        insertAggregate("DAY", bucket, "k-1", "USD");
        assertThatThrownBy(() -> insertAggregate("HOUR", bucket, "k-1", "USD"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void retentionDeletionBatchIsUniquePerDomainAndCutoff() throws Exception {
        insertBatch("TRACE", Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
        assertThatThrownBy(() -> insertBatch("TRACE",
                Timestamp.from(Instant.parse("2026-09-01T00:00:00Z"))))
                .isInstanceOf(SQLException.class);
        insertBatch("USAGE_LEDGER", Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
        assertThatThrownBy(() -> insertBatch("SYSLOG",
                Timestamp.from(Instant.parse("2026-09-02T00:00:00Z"))))
                .isInstanceOf(SQLException.class);
    }

    // ---------- fixtures ----------

    private void insertReservation(String requestId, String status) throws SQLException {
        execute("INSERT INTO budget_reservation (id, created_at, updated_at, request_id, "
                + "application_id, application_key_id, reserved_tokens, reserved_amount, currency, "
                + "expires_at, status) VALUES ('" + uuid() + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '"
                + requestId + "', '" + uuid() + "', '" + uuid() + "', 100, 1.00000000, 'USD', "
                + "DATEADD('SECOND', 60, CURRENT_TIMESTAMP), '" + status + "')");
    }

    private void insertLedger(String eventKey, String eventType) throws SQLException {
        String eventColumn = eventType == null ? "" : "event_type, ";
        String eventValue = eventType == null ? "" : "'" + eventType + "', ";
        execute("INSERT INTO usage_ledger (id, created_at, event_key, application_id, "
                + "input_tokens, output_tokens, token_delta, amount_delta, currency, usage_source, "
                + eventColumn + "price_snapshot) VALUES ('" + uuid() + "', CURRENT_TIMESTAMP, '"
                + eventKey + "', '" + uuid() + "', 0, 0, 0, 0, 'USD', 'ACTUAL', "
                + eventValue + "'{}')");
    }

    private void insertAttempt(String traceId, int sequence) throws SQLException {
        execute("INSERT INTO attempt (id, created_at, updated_at, trace_id, sequence, attempt_type, "
                + "channel_id, upstream_model_id, channel_credential_id, channel_name_snapshot, "
                + "upstream_model_name_snapshot, model_id_snapshot, channel_credential_name_snapshot, "
                + "status, started_at, endpoint_host) VALUES ('" + uuid() + "', CURRENT_TIMESTAMP, "
                + "CURRENT_TIMESTAMP, '" + traceId + "', " + sequence + ", 'INITIAL', '" + uuid()
                + "', '" + uuid() + "', '" + uuid() + "', 'channel', 'model', 'gpt-test', 'key', "
                + "'RUNNING', CURRENT_TIMESTAMP, 'example.invalid')");
    }

    private void insertAggregate(String granularity, Timestamp bucketStart, String dimensionKey,
                                 String currency) throws SQLException {
        execute("INSERT INTO usage_aggregate (id, created_at, updated_at, granularity, bucket_start, "
                + "bucket_end, dimension_key, application, trace_status, requested_stream, currency, "
                + "dimension_names) VALUES ('" + uuid() + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '"
                + granularity + "', '" + bucketStart + "', '" + bucketStart + "', '" + dimensionKey
                + "', 'app', 'SUCCEEDED', FALSE, '" + currency + "', '{}')");
    }

    private void insertBatch(String domain, Timestamp cutoff) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO retention_deletion_batch (id, created_at, updated_at, domain, "
                             + "cutoff_at) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)")) {
            statement.setString(1, uuid());
            statement.setString(2, domain);
            statement.setTimestamp(3, cutoff);
            statement.executeUpdate();
        }
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
