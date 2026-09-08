package com.lightai.storage.application;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.capacity.CapacityStore;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.ApplicationQuotaPort;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** JDBC 预算事实 + CapacityStore 分钟窗口组成的企业应用额度实现。 */
public final class JdbcApplicationQuotaPort extends AbstractJdbcRepository
        implements ApplicationQuotaPort {

    private static final System.Logger log = System.getLogger(JdbcApplicationQuotaPort.class.getName());
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8);
    private static final long RESERVATION_LEASE_SECONDS = 180;

    private final DataSource dataSource;
    private final CapacityStore capacityStore;
    private final Clock clock;

    public JdbcApplicationQuotaPort(DataSource dataSource, CapacityStore capacityStore, Clock clock,
                                    String schemaName) {
        super(schemaName);
        this.dataSource = dataSource;
        this.capacityStore = capacityStore;
        this.clock = clock;
    }

    public JdbcApplicationQuotaPort(DataSource dataSource, CapacityStore capacityStore, Clock clock) {
        this(dataSource, capacityStore, clock,
                com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public Reservation reserve(AccessTokenPort.Principal principal, String requestId,
                               long estimatedTokens, List<AmountEstimate> amountEstimates) {
        if (principal == null || principal.applicationId() == null
                || principal.applicationKeyId() == null) {
            return new Reservation(UUID.randomUUID().toString(), requestId,
                    principal == null ? null : principal.applicationId(),
                    principal == null ? null : principal.applicationKeyId(), null, false);
        }
        UUID applicationId = uuid(principal.applicationId(), "应用 ID 不合法");
        UUID applicationKeyId = uuid(principal.applicationKeyId(), "应用密钥 ID 不合法");
        if (requestId == null || requestId.isBlank()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "request_id 不能为空");
        }
        long safeTokens = Math.max(0, estimatedTokens);
        DbReservation db = inTransaction(connection -> reserveBudget(
                connection, requestId, applicationId, applicationKeyId, safeTokens,
                amountEstimates == null ? List.of() : amountEstimates));

        CapacityStore.ReservationHandle capacity = null;
        try {
            if (db.applicationLimit() != null || db.applicationKeyLimit() != null) {
                capacity = capacityStore.reserveApplication(applicationId, applicationKeyId,
                        safeTokens, db.applicationLimit(), db.applicationKeyLimit());
            }
            return new Reservation(db.id().toString(), requestId, applicationId.toString(),
                    applicationKeyId.toString(),
                    capacity == null ? null : capacity.reservationId().toString(), true);
        } catch (RuntimeException capacityFailure) {
            try {
                releaseBudget(db.id());
            } catch (RuntimeException compensationFailure) {
                capacityFailure.addSuppressed(compensationFailure);
            }
            throw capacityFailure;
        }
    }

    @Override
    public void settle(Reservation reservation, Settlement settlement) {
        if (reservation == null || !reservation.managed()) return;
        UUID reservationId = uuid(reservation.reservationId(), "额度预占 ID 不合法");
        inTransaction(connection -> {
            settleBudget(connection, reservationId, settlement);
            return null;
        });
        terminalCapacity(reservation.capacityReservationId(), settlement.totalTokens(), true);
    }

    @Override
    public void release(Reservation reservation, String reason) {
        if (reservation == null || !reservation.managed()) return;
        UUID reservationId = uuid(reservation.reservationId(), "额度预占 ID 不合法");
        releaseBudget(reservationId);
        terminalCapacity(reservation.capacityReservationId(), 0, false);
    }

    @Override
    public int reclaimExpired(Instant instant) {
        OffsetDateTime cutoff = (instant == null ? clock.instant() : instant).atOffset(ZoneOffset.UTC);
        List<UUID> ids;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseDialect dialect = dialect(connection);
            String sql = "SELECT id FROM " + qualify(connection, "budget_reservation")
                    + " WHERE status='ACTIVE' AND expires_at<=? ORDER BY expires_at "
                    + dialect.limitOffsetClause(100, 0);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, cutoff);
                try (ResultSet resultSet = statement.executeQuery()) {
                    java.util.ArrayList<UUID> found = new java.util.ArrayList<>();
                    while (resultSet.next()) found.add(dialect.readUuid(resultSet, 1));
                    ids = List.copyOf(found);
                }
            }
        } catch (SQLException failure) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "应用额度回收状态不可用");
        }
        int reclaimed = 0;
        for (UUID id : ids) {
            if (releaseBudget(id)) reclaimed++;
        }
        return reclaimed;
    }

    private DbReservation reserveBudget(Connection connection, String requestId,
                                        UUID applicationId, UUID applicationKeyId,
                                        long estimatedTokens,
                                        List<AmountEstimate> amountEstimates) throws SQLException {
        KeyLimits key = lockAndValidateKey(connection, applicationId, applicationKeyId);
        ApplicationQuotaRecord quota = lockQuota(connection, applicationId);
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        if ((quota.periodStart() != null && now.isBefore(quota.periodStart()))
                || (quota.periodEnd() != null && !now.isBefore(quota.periodEnd()))) {
            throw new LightAiException(ErrorCode.APPLICATION_QUOTA_INACTIVE,
                    "应用额度周期尚未生效或已经结束");
        }
        BigDecimal reservedAmount = amountForPolicy(quota, amountEstimates);
        if (quota.tokenLimit() != null
                && quota.tokensUsed() + quota.tokensReserved() + estimatedTokens > quota.tokenLimit()) {
            throw new LightAiException(ErrorCode.APPLICATION_TOKEN_QUOTA_EXHAUSTED,
                    "应用 Token 额度不足");
        }
        if (quota.amountLimit() != null
                && quota.amountUsed().add(quota.amountReserved()).add(reservedAmount)
                .compareTo(quota.amountLimit()) > 0) {
            throw new LightAiException(ErrorCode.APPLICATION_AMOUNT_BUDGET_EXHAUSTED,
                    "应用金额预算不足");
        }
        if (findReservationByRequest(connection, requestId)) {
            throw new LightAiException(ErrorCode.TRACE_ID_CONFLICT, "request_id 已存在");
        }

        UUID id = UUID.randomUUID();
        DatabaseDialect dialect = dialect(connection);
        String insert = "INSERT INTO " + qualify(connection, "budget_reservation")
                + " (id, created_at, updated_at, request_id, application_id, application_key_id, "
                + "reserved_tokens, reserved_amount, currency, expires_at, status, terminal_at) "
                + "VALUES (?, " + dialect.nowFunction() + ", " + dialect.nowFunction()
                + ", ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NULL)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            dialect.bindUuid(statement, 1, id);
            statement.setString(2, requestId);
            dialect.bindUuid(statement, 3, applicationId);
            dialect.bindUuid(statement, 4, applicationKeyId);
            statement.setLong(5, estimatedTokens);
            statement.setBigDecimal(6, reservedAmount);
            statement.setString(7, quota.currency());
            statement.setObject(8, now.plusSeconds(RESERVATION_LEASE_SECONDS));
            statement.executeUpdate();
        }
        updateQuotaReservation(connection, applicationId, estimatedTokens, reservedAmount);
        return new DbReservation(id,
                limit(quota.rpm(), quota.tpm()), limit(key.rpm(), key.tpm()));
    }

    private KeyLimits lockAndValidateKey(Connection connection, UUID applicationId,
                                         UUID applicationKeyId) throws SQLException {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT k.status key_status, k.expires_at, k.rpm key_rpm, k.tpm key_tpm, "
                + "a.status application_status FROM " + qualify(connection, "application_key")
                + " k JOIN " + qualify(connection, "application")
                + " a ON a.id=k.application_id WHERE k.id=? AND k.application_id=? "
                + dialect.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationKeyId);
            dialect.bindUuid(statement, 2, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用密钥无效");
                }
                OffsetDateTime expiresAt = dialect.readOffsetDateTime(resultSet, "expires_at");
                OffsetDateTime now = OffsetDateTime.now(clock);
                if (!"ACTIVE".equals(resultSet.getString("key_status"))
                        || !"ACTIVE".equals(resultSet.getString("application_status"))
                        || (expiresAt != null && !now.isBefore(expiresAt))) {
                    throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, "应用或应用密钥不可用");
                }
                return new KeyLimits(getIntOrNull(resultSet, "key_rpm"),
                        getLongOrNull(resultSet, "key_tpm"));
            }
        }
    }

    private ApplicationQuotaRecord lockQuota(Connection connection, UUID applicationId)
            throws SQLException {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT id, application_id, token_limit, amount_limit, currency, rpm, tpm, "
                + "period_type, period_start, period_end, tokens_used, tokens_reserved, amount_used, "
                + "amount_reserved, version, created_at, updated_at FROM "
                + qualify(connection, "application_quota_policy") + " WHERE application_id=? "
                + dialect.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, applicationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE,
                            "应用缺少额度策略");
                }
                return new ApplicationQuotaRecord(
                        dialect.readUuid(resultSet, "id"), dialect.readUuid(resultSet, "application_id"),
                        getLongOrNull(resultSet, "token_limit"), resultSet.getBigDecimal("amount_limit"),
                        resultSet.getString("currency"), getIntOrNull(resultSet, "rpm"),
                        getLongOrNull(resultSet, "tpm"), resultSet.getString("period_type"),
                        dialect.readOffsetDateTime(resultSet, "period_start"),
                        dialect.readOffsetDateTime(resultSet, "period_end"),
                        resultSet.getLong("tokens_used"), resultSet.getLong("tokens_reserved"),
                        resultSet.getBigDecimal("amount_used"), resultSet.getBigDecimal("amount_reserved"),
                        resultSet.getLong("version"), dialect.readOffsetDateTime(resultSet, "created_at"),
                        dialect.readOffsetDateTime(resultSet, "updated_at"));
            }
        }
    }

    private BigDecimal amountForPolicy(ApplicationQuotaRecord quota,
                                       List<AmountEstimate> estimates) {
        if (quota.amountLimit() == null) return ZERO;
        BigDecimal max = ZERO;
        for (AmountEstimate estimate : estimates) {
            if (estimate.amount().signum() == 0) continue;
            if (estimate.currency() == null
                    || !quota.currency().equalsIgnoreCase(estimate.currency())) {
                throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE,
                        "路由候选币种与应用金额预算币种不一致");
            }
            max = max.max(estimate.amount());
        }
        return max;
    }

    private boolean findReservationByRequest(Connection connection, String requestId)
            throws SQLException {
        String sql = "SELECT 1 FROM " + qualify(connection, "budget_reservation")
                + " WHERE request_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void updateQuotaReservation(Connection connection, UUID applicationId,
                                        long tokens, BigDecimal amount) throws SQLException {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "application_quota_policy")
                + " SET tokens_reserved=tokens_reserved+?, amount_reserved=amount_reserved+?, "
                + "version=version+1, updated_at=" + dialect.nowFunction() + " WHERE application_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tokens);
            statement.setBigDecimal(2, amount);
            dialect.bindUuid(statement, 3, applicationId);
            if (statement.executeUpdate() != 1) throw new SQLException("额度策略更新数量异常");
        }
    }

    private void settleBudget(Connection connection, UUID reservationId, Settlement settlement)
            throws SQLException {
        BudgetRow row = lockReservation(connection, reservationId);
        if (row == null || !"ACTIVE".equals(row.status())) return;
        ApplicationQuotaRecord quota = lockQuota(connection, row.applicationId());
        if (quota.amountLimit() != null && settlement.amount().signum() > 0
                && (settlement.currency() == null
                || !quota.currency().equalsIgnoreCase(settlement.currency()))) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE,
                    "实际结算币种与应用金额预算币种不一致");
        }
        BigDecimal actualAmount = settlement.amount();
        DatabaseDialect dialect = dialect(connection);
        String quotaSql = "UPDATE " + qualify(connection, "application_quota_policy")
                + " SET tokens_reserved=CASE WHEN tokens_reserved>=? THEN tokens_reserved-? ELSE 0 END, "
                + "amount_reserved=CASE WHEN amount_reserved>=? THEN amount_reserved-? ELSE 0 END, "
                + "tokens_used=tokens_used+?, amount_used=amount_used+?, version=version+1, updated_at="
                + dialect.nowFunction() + " WHERE application_id=?";
        try (PreparedStatement statement = connection.prepareStatement(quotaSql)) {
            statement.setLong(1, row.reservedTokens());
            statement.setLong(2, row.reservedTokens());
            statement.setBigDecimal(3, row.reservedAmount());
            statement.setBigDecimal(4, row.reservedAmount());
            statement.setLong(5, settlement.totalTokens());
            statement.setBigDecimal(6, actualAmount);
            dialect.bindUuid(statement, 7, row.applicationId());
            statement.executeUpdate();
        }
        insertLedger(connection, row, settlement);
        terminalReservation(connection, reservationId, "SETTLED");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + qualify(connection, "application") + " SET last_called_at="
                        + dialect.nowFunction() + ", updated_at=" + dialect.nowFunction()
                        + " WHERE id=?")) {
            dialect.bindUuid(statement, 1, row.applicationId());
            statement.executeUpdate();
        }
    }

    private void insertLedger(Connection connection, BudgetRow row, Settlement settlement)
            throws SQLException {
        DatabaseDialect dialect = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "usage_ledger")
                + " (id, created_at, event_key, request_id, application_id, application_key_id, "
                + "virtual_model_id, channel_id, input_tokens, output_tokens, token_delta, "
                + "amount_delta, currency, usage_source, price_snapshot) VALUES (?, "
                + dialect.nowFunction() + ", ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, "
                + dialect.jsonPlaceholder() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, UUID.randomUUID());
            statement.setString(2, "SETTLEMENT:" + row.requestId());
            statement.setString(3, row.requestId());
            dialect.bindUuid(statement, 4, row.applicationId());
            dialect.bindUuid(statement, 5, row.applicationKeyId());
            bindUuidOrNull(statement, 6, settlement.virtualModelId(), dialect);
            statement.setLong(7, settlement.inputTokens());
            statement.setLong(8, settlement.outputTokens());
            statement.setLong(9, settlement.totalTokens());
            statement.setBigDecimal(10, settlement.amount());
            statement.setString(11, settlement.currency() == null ? row.currency() : settlement.currency());
            statement.setString(12, settlement.usageSource() == null ? "ESTIMATED" : settlement.usageSource());
            dialect.bindJson(statement, 13, priceSnapshotJson(settlement));
            statement.executeUpdate();
        }
    }

    private boolean releaseBudget(UUID reservationId) {
        return inTransaction(connection -> {
            BudgetRow row = lockReservation(connection, reservationId);
            if (row == null || !"ACTIVE".equals(row.status())) return false;
            lockQuota(connection, row.applicationId());
            DatabaseDialect dialect = dialect(connection);
            String quotaSql = "UPDATE " + qualify(connection, "application_quota_policy")
                    + " SET tokens_reserved=CASE WHEN tokens_reserved>=? THEN tokens_reserved-? ELSE 0 END, "
                    + "amount_reserved=CASE WHEN amount_reserved>=? THEN amount_reserved-? ELSE 0 END, "
                    + "version=version+1, updated_at=" + dialect.nowFunction() + " WHERE application_id=?";
            try (PreparedStatement statement = connection.prepareStatement(quotaSql)) {
                statement.setLong(1, row.reservedTokens());
                statement.setLong(2, row.reservedTokens());
                statement.setBigDecimal(3, row.reservedAmount());
                statement.setBigDecimal(4, row.reservedAmount());
                dialect.bindUuid(statement, 5, row.applicationId());
                statement.executeUpdate();
            }
            terminalReservation(connection, reservationId, "RELEASED");
            return true;
        });
    }

    private BudgetRow lockReservation(Connection connection, UUID id) throws SQLException {
        DatabaseDialect dialect = dialect(connection);
        String sql = "SELECT id, request_id, application_id, application_key_id, reserved_tokens, "
                + "reserved_amount, currency, status FROM " + qualify(connection, "budget_reservation")
                + " WHERE id=? " + dialect.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            dialect.bindUuid(statement, 1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                return new BudgetRow(dialect.readUuid(resultSet, "id"),
                        resultSet.getString("request_id"),
                        dialect.readUuid(resultSet, "application_id"),
                        dialect.readUuid(resultSet, "application_key_id"),
                        resultSet.getLong("reserved_tokens"),
                        resultSet.getBigDecimal("reserved_amount"),
                        resultSet.getString("currency"), resultSet.getString("status"));
            }
        }
    }

    private void terminalReservation(Connection connection, UUID id, String status)
            throws SQLException {
        DatabaseDialect dialect = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "budget_reservation")
                + " SET status=?, terminal_at=" + dialect.nowFunction() + ", updated_at="
                + dialect.nowFunction() + " WHERE id=? AND status='ACTIVE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            dialect.bindUuid(statement, 2, id);
            statement.executeUpdate();
        }
    }

    private void terminalCapacity(String id, long actualTokens, boolean settle) {
        if (id == null) return;
        try {
            UUID capacityId = UUID.fromString(id);
            if (settle) capacityStore.settle(capacityId, actualTokens, true);
            else capacityStore.release(capacityId);
        } catch (RuntimeException failure) {
            log.log(System.Logger.Level.WARNING,
                    "应用分钟窗口终态写入失败 reservation_id={0} exception={1}",
                    id, failure.getClass().getSimpleName());
        }
    }

    private CapacityStore.ScopeLimit limit(Integer rpm, Long tpm) {
        if (rpm == null && tpm == null) return null;
        return new CapacityStore.ScopeLimit(rpm == null ? null : rpm.longValue(), tpm, null);
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (RuntimeException | SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (failure instanceof LightAiException lightAi) throw lightAi;
                throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE,
                        "应用额度状态不可用");
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (LightAiException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE,
                    "应用额度状态不可用");
        }
    }

    private static UUID uuid(String value, String message) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException failure) {
            throw new LightAiException(ErrorCode.ACCESS_TOKEN_INVALID, message);
        }
    }

    private static void bindUuidOrNull(PreparedStatement statement, int index, String value,
                                       DatabaseDialect dialect) throws SQLException {
        if (value == null || value.isBlank()) statement.setObject(index, null);
        else dialect.bindUuid(statement, index, uuid(value, "虚拟模型 ID 不合法"));
    }

    private static String priceSnapshotJson(Settlement settlement) {
        return "{\"provider_model_id\":\"" + escape(settlement.providerModelId())
                + "\",\"input_price\":\"" + escape(settlement.inputPrice())
                + "\",\"output_price\":\"" + escape(settlement.outputPrice())
                + "\",\"price_unit\":" + settlement.priceUnit()
                + ",\"currency\":\"" + escape(settlement.currency()) + "\"}";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record DbReservation(UUID id, CapacityStore.ScopeLimit applicationLimit,
                                 CapacityStore.ScopeLimit applicationKeyLimit) {
    }

    private record KeyLimits(Integer rpm, Long tpm) {
    }

    private record BudgetRow(UUID id, String requestId, UUID applicationId, UUID applicationKeyId,
                             long reservedTokens, BigDecimal reservedAmount, String currency,
                             String status) {
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
