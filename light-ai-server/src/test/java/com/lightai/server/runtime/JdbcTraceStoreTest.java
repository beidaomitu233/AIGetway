package com.lightai.server.runtime;

import com.lightai.admin.trace.TraceFinalizer;
import com.lightai.admin.usage.UsageAggregator;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.trace.TraceStore;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class JdbcTraceStoreTest {

    private JdbcDataSource dataSource;
    private ConfigSnapshotPort snapshotPort;
    private TraceFinalizer traceFinalizer;
    private UsageAggregator usageAggregator;
    private JdbcTraceStore traceStore;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:trace_test_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE trace ("
                    + "id VARCHAR(36) PRIMARY KEY, "
                    + "created_at TIMESTAMP NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL, "
                    + "trace_id VARCHAR(128) NOT NULL UNIQUE, "
                    + "application VARCHAR(64) NOT NULL, "
                    + "project VARCHAR(64), "
                    + "tenant VARCHAR(64), "
                    + "request_user VARCHAR(128), "
                    + "tags VARCHAR(1000), "
                    + "source_mode VARCHAR(32) NOT NULL, "
                    + "invocation_source VARCHAR(16) NOT NULL, "
                    + "alias_id VARCHAR(36), "
                    + "alias VARCHAR(64), "
                    + "config_snapshot_no BIGINT NOT NULL, "
                    + "requested_stream TINYINT NOT NULL DEFAULT 0, "
                    + "response_committed TINYINT NOT NULL DEFAULT 0, "
                    + "status VARCHAR(24) NOT NULL DEFAULT 'RUNNING', "
                    + "started_at TIMESTAMP NOT NULL, "
                    + "deadline_at TIMESTAMP NOT NULL, "
                    + "ended_at TIMESTAMP, "
                    + "total_ms INT, "
                    + "first_token_ms INT, "
                    + "queued_ms INT NOT NULL DEFAULT 0, "
                    + "attempt_count INT NOT NULL DEFAULT 0, "
                    + "retry_count INT NOT NULL DEFAULT 0, "
                    + "credential_failover_count INT NOT NULL DEFAULT 0, "
                    + "fallback_count INT NOT NULL DEFAULT 0, "
                    + "final_attempt_id VARCHAR(36), "
                    + "final_channel_id VARCHAR(36), "
                    + "final_upstream_model_id VARCHAR(36), "
                    + "final_channel_credential_id VARCHAR(36), "
                    + "access_credential_id VARCHAR(36), "
                    + "final_channel_name VARCHAR(128), "
                    + "final_upstream_model_name VARCHAR(128), "
                    + "access_credential_name VARCHAR(128), "
                    + "input_tokens BIGINT NOT NULL DEFAULT 0, "
                    + "output_tokens BIGINT NOT NULL DEFAULT 0, "
                    + "total_tokens BIGINT NOT NULL DEFAULT 0, "
                    + "response_input_tokens BIGINT NOT NULL DEFAULT 0, "
                    + "response_output_tokens BIGINT NOT NULL DEFAULT 0, "
                    + "response_total_tokens BIGINT NOT NULL DEFAULT 0, "
                    + "usage_source VARCHAR(12), "
                    + "input_cost DECIMAL(30,8) NOT NULL DEFAULT 0, "
                    + "output_cost DECIMAL(30,8) NOT NULL DEFAULT 0, "
                    + "total_cost DECIMAL(30,8) NOT NULL DEFAULT 0, "
                    + "currency CHAR(3) NOT NULL, "
                    + "finish_reason VARCHAR(32), "
                    + "error_code VARCHAR(64), "
                    + "error_category VARCHAR(64), "
                    + "error_stage VARCHAR(64), "
                    + "error_summary VARCHAR(1000), "
                    + "retryable TINYINT NOT NULL DEFAULT 0, "
                    + "request_summary VARCHAR(1000), "
                    + "client_ip VARCHAR(45), "
                    + "user_agent VARCHAR(512), "
                    + "owner_instance_id VARCHAR(36), "
                    + "lease_expires_at TIMESTAMP, "
                    + "terminal_version BIGINT NOT NULL DEFAULT 0"
                    + ")");

            stmt.execute("CREATE TABLE attempt ("
                    + "id VARCHAR(36) PRIMARY KEY, "
                    + "created_at TIMESTAMP NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL, "
                    + "trace_id VARCHAR(128) NOT NULL, "
                    + "`sequence` INT NOT NULL, "
                    + "attempt_type VARCHAR(32) NOT NULL, "
                    + "route_candidate_id VARCHAR(36), "
                    + "channel_id VARCHAR(36) NOT NULL, "
                    + "upstream_model_id VARCHAR(36) NOT NULL, "
                    + "channel_credential_id VARCHAR(36) NOT NULL, "
                    + "channel_name_snapshot VARCHAR(128) NOT NULL, "
                    + "upstream_model_name_snapshot VARCHAR(128) NOT NULL, "
                    + "model_id_snapshot VARCHAR(128) NOT NULL, "
                    + "channel_credential_name_snapshot VARCHAR(128) NOT NULL, "
                    + "status VARCHAR(16) NOT NULL, "
                    + "started_at TIMESTAMP NOT NULL, "
                    + "channel_started_at TIMESTAMP, "
                    + "response_headers_at TIMESTAMP, "
                    + "first_token_at TIMESTAMP, "
                    + "ended_at TIMESTAMP, "
                    + "dispatch_ms INT, "
                    + "response_header_ms INT, "
                    + "first_token_ms INT, "
                    + "total_ms INT, "
                    + "endpoint_host VARCHAR(255) NOT NULL, "
                    + "http_status INT, "
                    + "channel_request_id VARCHAR(256), "
                    + "response_committed TINYINT NOT NULL DEFAULT 0, "
                    + "finish_reason VARCHAR(32), "
                    + "error_code VARCHAR(64), "
                    + "error_category VARCHAR(64), "
                    + "error_stage VARCHAR(64), "
                    + "error_summary VARCHAR(1000), "
                    + "retryable TINYINT NOT NULL DEFAULT 0, "
                    + "retry_after_ms INT, "
                    + "resolved_parameters VARCHAR(1000), "
                    + "input_tokens BIGINT NOT NULL DEFAULT 0, "
                    + "output_tokens BIGINT NOT NULL DEFAULT 0, "
                    + "total_tokens BIGINT NOT NULL DEFAULT 0, "
                    + "usage_source VARCHAR(12), "
                    + "input_price DECIMAL(20,8), "
                    + "output_price DECIMAL(20,8), "
                    + "price_unit INT, "
                    + "currency CHAR(3), "
                    + "input_cost DECIMAL(30,8) NOT NULL DEFAULT 0, "
                    + "output_cost DECIMAL(30,8) NOT NULL DEFAULT 0, "
                    + "total_cost DECIMAL(30,8) NOT NULL DEFAULT 0, "
                    + "settled_at TIMESTAMP"
                    + ")");
        }

        snapshotPort = mock(ConfigSnapshotPort.class);
        traceFinalizer = mock(TraceFinalizer.class);
        usageAggregator = mock(UsageAggregator.class);

        traceStore = new JdbcTraceStore(dataSource, snapshotPort, traceFinalizer, usageAggregator, Clock.systemUTC());
    }

    @Test
    void testCreateAndTraceConflict() {
        TraceStore.TraceHandle handle1 = traceStore.create("client-trace-1", "gpt-4o", "test-app");
        assertNotNull(handle1);
        assertEquals("client-trace-1", handle1.traceId());

        LightAiException ex = assertThrows(LightAiException.class, () ->
                traceStore.create("client-trace-1", "gpt-4o", "test-app"));
        assertEquals(ErrorCode.TRACE_ID_CONFLICT, ex.code());

        TraceStore.TraceHandle handle2 = traceStore.create(null, "gpt-4o", "test-app");
        assertNotNull(handle2);
        assertNotNull(handle2.traceId());
    }

    @Test
    void testAttemptTimelineAndFinalize() {
        TraceStore.TraceHandle handle = traceStore.create("trace-timeline-1", "gpt-4o", "test-app");
        String traceId = handle.traceId();

        assertFalse(traceStore.committed(traceId));

        // Start Attempt 1
        String attempt1 = traceStore.startAttempt(traceId, null, "OPENAI", "gpt-4o");
        assertNotNull(attempt1);

        // Finish Attempt 1 as FAILED
        traceStore.finishAttempt(traceId, attempt1, "FAILED", "TIMEOUT", 0, 0, "ESTIMATED", "0", "USD", false);

        // Start Attempt 2 (Fallback)
        String attempt2 = traceStore.startAttempt(traceId, null, "ANTHROPIC", "claude-3-5-sonnet");
        assertNotNull(attempt2);

        // Finish Attempt 2 as SUCCEEDED
        traceStore.finishAttempt(traceId, attempt2, "SUCCEEDED", null, 100, 200, "ACTUAL", "0.005", "USD", false);

        // Mark committed
        traceStore.markCommitted(traceId);
        assertTrue(traceStore.committed(traceId));

        // Finalize Trace
        traceStore.finalizeTrace(traceId, "SUCCEEDED");

        // Verify Finalizer and Aggregator were called
        verify(traceFinalizer).finalizeTrace(traceId);
        verify(usageAggregator).processPending(anyInt());

        // Verify attempts query
        List<TraceStore.AttemptView> attempts = traceStore.attempts(traceId);
        assertEquals(2, attempts.size());
        assertEquals("FAILED", attempts.get(0).status());
        assertEquals("TIMEOUT", attempts.get(0).errorCode());
        assertEquals("SUCCEEDED", attempts.get(1).status());
        assertNull(attempts.get(1).errorCode());
    }

    @Test
    void testAttemptIdentityPriceAndCostComponentsPersisted() throws Exception {
        TraceStore.TraceHandle handle = traceStore.create("trace-identity-1", "gpt-4o", "test-app");
        String traceId = handle.traceId();

        java.util.UUID candidateId = java.util.UUID.randomUUID();
        java.util.UUID channelId = java.util.UUID.randomUUID();
        java.util.UUID upstreamModelId = java.util.UUID.randomUUID();
        java.util.UUID credentialId = java.util.UUID.randomUUID();
        TraceStore.AttemptIdentity identity = new TraceStore.AttemptIdentity(
                candidateId, channelId, upstreamModelId, credentialId,
                "OPENAI", "gpt-4o", "sk-****abcd",
                new java.math.BigDecimal("0.00000015"), new java.math.BigDecimal("0.00000060"),
                1000, "USD");
        String attemptId = traceStore.startAttempt(traceId, identity);

        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT route_candidate_id, channel_id, upstream_model_id, channel_credential_id, "
                             + "channel_credential_name_snapshot, input_price, output_price, price_unit, currency "
                             + "FROM attempt WHERE trace_id = ? AND id = ?")) {
            ps.setString(1, traceId);
            ps.setString(2, attemptId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(candidateId.toString(), rs.getString("route_candidate_id"));
                assertEquals(channelId.toString(), rs.getString("channel_id"));
                assertEquals(upstreamModelId.toString(), rs.getString("upstream_model_id"));
                assertEquals(credentialId.toString(), rs.getString("channel_credential_id"));
                // Key 只落掩码快照
                assertEquals("sk-****abcd", rs.getString("channel_credential_name_snapshot"));
                assertEquals(0, new java.math.BigDecimal("0.00000015")
                        .compareTo(rs.getBigDecimal("input_price")));
                assertEquals(1000, rs.getInt("price_unit"));
                assertEquals("USD", rs.getString("currency"));
            }
        }

        traceStore.finishAttempt(traceId, attemptId, "SUCCEEDED", null, 100, 200, "ACTUAL",
                new java.math.BigDecimal("0.0000150"), new java.math.BigDecimal("0.0001200"),
                new java.math.BigDecimal("0.0001350"), "USD", false);

        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT input_cost, output_cost, total_cost, settled_at FROM attempt "
                             + "WHERE trace_id = ? AND id = ?")) {
            ps.setString(1, traceId);
            ps.setString(2, attemptId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, new java.math.BigDecimal("0.0000150").compareTo(rs.getBigDecimal("input_cost")));
                assertEquals(0, new java.math.BigDecimal("0.0001200").compareTo(rs.getBigDecimal("output_cost")));
                assertEquals(0, new java.math.BigDecimal("0.0001350").compareTo(rs.getBigDecimal("total_cost")));
                assertNotNull(rs.getTimestamp("settled_at"));
            }
        }
    }

    @Test
    void testFinalizeExpiredConvergesRunningTraceIdempotently() throws Exception {
        TraceStore.TraceHandle handle = traceStore.create("trace-expired-1", "gpt-4o", "test-app");
        String traceId = handle.traceId();
        String attemptId = traceStore.startAttempt(traceId, null, "OPENAI", "gpt-4o");

        // 模拟进程崩溃：deadline 已过但仍处于 RUNNING
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE trace SET deadline_at = '2020-01-01 00:00:00' WHERE trace_id = '" + traceId + "'");
        }

        java.time.Instant now = java.time.Instant.now();
        int converged = traceStore.finalizeExpired(now, "TOTAL_TIMEOUT");
        assertEquals(1, converged);
        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT status FROM trace WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("FAILED", rs.getString("status"));
            }
        }

        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT status, error_code FROM attempt WHERE trace_id = ? AND id = ?")) {
            ps.setString(1, traceId);
            ps.setString(2, attemptId);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("FAILED", rs.getString("status"));
                assertEquals("TOTAL_TIMEOUT", rs.getString("error_code"));
            }
        }

        // 幂等：重复收敛不再返回数量
        assertEquals(0, traceStore.finalizeExpired(now.plusSeconds(60), "TOTAL_TIMEOUT"));
    }
}
