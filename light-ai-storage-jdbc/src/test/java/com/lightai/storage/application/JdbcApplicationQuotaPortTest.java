package com.lightai.storage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.capacity.InMemoryCapacityStore;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.ApplicationQuotaPort;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcApplicationQuotaPortTest {

    private JdbcDataSource dataSource;
    private JdbcApplicationRepository applications;
    private InMemoryCapacityStore capacity;
    private JdbcApplicationQuotaPort port;
    private UUID applicationId;
    private UUID keyId;
    private AccessTokenPort.Principal principal;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:application_quota_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        new DefaultSchemaMigrator(dataSource).migrate();
        applications = new JdbcApplicationRepository();
        capacity = new InMemoryCapacityStore();
        port = new JdbcApplicationQuotaPort(dataSource, capacity,
                Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC));
        applicationId = UUID.randomUUID();
        keyId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            applications.insert(connection, new ApplicationRecord(applicationId, "billing-app",
                    "计费应用", "财务", "owner", "负责人", "PROD", null,
                    "ACTIVE", null, 1, null, null));
            applications.insertQuota(connection, new ApplicationQuotaRecord(UUID.randomUUID(),
                    applicationId, 1_000L, new BigDecimal("10.00000000"), "CNY",
                    10, 1_000L, "MONTH", null, null, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, 1, null, null));
            new JdbcApplicationKeyRepository().insert(connection, new ApplicationKeyRecord(
                    keyId, applicationId, "生产密钥", "lai_test", "lai_****test",
                    new byte[]{1, 2, 3}, 1, 1, List.of(), null, 5, 800L,
                    "ACTIVE", null, null, null, null, 1, null, null));
        }
        principal = AccessTokenPort.Principal.enterprise("billing-app", List.of("chat-default"),
                applicationId.toString(), keyId.toString(), 5, 800L);
    }

    @Test
    void reservesSettlesLedgerAndAppliesTerminalOnlyOnce() throws Exception {
        var reservation = port.reserve(principal, "req-settle", 600,
                List.of(new ApplicationQuotaPort.AmountEstimate("CNY", new BigDecimal("2.5"))));
        assertThat(quota().tokensReserved()).isEqualTo(600);
        assertThat(quota().amountReserved()).isEqualByComparingTo("2.5");
        assertThat(capacity.usage("application", applicationId).tpmReserved()).isEqualTo(600);
        assertThat(capacity.usage("application_key", keyId).rpmReserved()).isEqualTo(1);

        var settlement = new ApplicationQuotaPort.Settlement(180, 120,
                new BigDecimal("1.2"), "CNY", "ACTUAL", null, null,
                "1", "2", 1_000_000);
        port.settle(reservation, settlement);
        port.settle(reservation, settlement);

        assertThat(quota().tokensReserved()).isZero();
        assertThat(quota().tokensUsed()).isEqualTo(300);
        assertThat(quota().amountUsed()).isEqualByComparingTo("1.2");
        assertThat(capacity.usage("application", applicationId).tpmReserved()).isEqualTo(300);
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(u.id), max(b.status) FROM budget_reservation b "
                             + "LEFT JOIN usage_ledger u ON u.request_id=b.request_id "
                             + "WHERE b.request_id='req-settle'")) {
            try (var result = statement.executeQuery()) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(1);
                assertThat(result.getString(2)).isEqualTo("SETTLED");
            }
        }
    }

    @Test
    void releaseIsIdempotentAndRestoresBudget() {
        var reservation = port.reserve(principal, "req-release", 400,
                List.of(new ApplicationQuotaPort.AmountEstimate("CNY", BigDecimal.ONE)));
        port.release(reservation, "CLIENT_CANCELLED");
        port.release(reservation, "CLIENT_CANCELLED");
        assertThat(quota().tokensReserved()).isZero();
        assertThat(quota().amountReserved()).isZero();
        assertThat(capacity.usage("application", applicationId).rpmReserved()).isZero();
    }

    @Test
    void reclaimsExpiredDatabaseReservationOnce() {
        port.reserve(principal, "req-expired", 400,
                List.of(new ApplicationQuotaPort.AmountEstimate("CNY", BigDecimal.ONE)));

        assertThat(port.reclaimExpired(Instant.parse("2026-09-08T10:03:01Z"))).isEqualTo(1);
        assertThat(port.reclaimExpired(Instant.parse("2026-09-08T10:03:02Z"))).isZero();
        assertThat(quota().tokensReserved()).isZero();
        assertThat(quota().amountReserved()).isZero();
    }

    @Test
    void rejectsTokenAmountAndCurrencyBeforeCreatingReservation() {
        assertThatThrownBy(() -> port.reserve(principal, "req-token", 1_001,
                List.of(new ApplicationQuotaPort.AmountEstimate("CNY", BigDecimal.ONE))))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.APPLICATION_TOKEN_QUOTA_EXHAUSTED);
        assertThatThrownBy(() -> port.reserve(principal, "req-amount", 100,
                List.of(new ApplicationQuotaPort.AmountEstimate("CNY", new BigDecimal("11")))))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.APPLICATION_AMOUNT_BUDGET_EXHAUSTED);
        assertThatThrownBy(() -> port.reserve(principal, "req-currency", 100,
                List.of(new ApplicationQuotaPort.AmountEstimate("USD", BigDecimal.ONE))))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.CONFIG_DATA_UNAVAILABLE);
        assertThat(quota().tokensReserved()).isZero();
    }

    @Test
    void concurrentReservationsCannotOversubscribeTokenBudget() throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> reserveAfter(start, "req-concurrent-1"));
            var second = executor.submit(() -> reserveAfter(start, "req-concurrent-2"));
            start.countDown();
            Object left = first.get();
            Object right = second.get();
            List<Object> outcomes = List.of(left, right);
            assertThat(outcomes).filteredOn(ApplicationQuotaPort.Reservation.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes).filteredOn(ErrorCode.APPLICATION_TOKEN_QUOTA_EXHAUSTED::equals)
                    .hasSize(1);
            assertThat(quota().tokensReserved()).isEqualTo(600);
            outcomes.stream().filter(ApplicationQuotaPort.Reservation.class::isInstance)
                    .map(ApplicationQuotaPort.Reservation.class::cast)
                    .forEach(reservation -> port.release(reservation, "TEST_DONE"));
        } finally {
            executor.shutdownNow();
        }
    }

    private Object reserveAfter(CountDownLatch start, String requestId) throws InterruptedException {
        start.await();
        try {
            return port.reserve(principal, requestId, 600,
                    List.of(new ApplicationQuotaPort.AmountEstimate("CNY", BigDecimal.ONE)));
        } catch (LightAiException failure) {
            return failure.code();
        }
    }

    private ApplicationQuotaRecord quota() {
        try (Connection connection = dataSource.getConnection()) {
            return applications.findQuota(connection, applicationId).orElseThrow();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
