package com.lightai.storage.governance;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;

import java.math.BigDecimal;
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
 * reliability_policy JDBC 仓储（DATABASE_PLAN §9）。
 * 同一 alias_id 至多一条启用；默认策略不入库（SYSTEM_DEFAULT 由服务层合成）。
 * 支持 PostgreSQL 与 MySQL 5.7 / 8.0 双方言自适应。
 */
public class JdbcReliabilityPolicyRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, name, alias_id, connect_timeout_ms, first_token_timeout_ms, total_timeout_ms, "
                    + "max_retries, max_credential_failovers, initial_backoff_ms, backoff_multiplier, "
                    + "jitter_percent, respect_retry_after, max_retry_after_ms, fallback_enabled, "
                    + "max_fallbacks, circuit_window_seconds, circuit_min_requests, circuit_failure_rate, "
                    + "circuit_open_seconds, circuit_half_open_probes, circuit_half_open_successes, "
                    + "enabled, version, created_at, updated_at";

    public record ReliabilityPolicyRow(
            UUID id, String name, UUID aliasId, Integer connectTimeoutMs, Integer firstTokenTimeoutMs,
            Integer totalTimeoutMs, Integer maxRetries, Integer maxCredentialFailovers,
            Integer initialBackoffMs, BigDecimal backoffMultiplier, Integer jitterPercent,
            Boolean respectRetryAfter, Integer maxRetryAfterMs, Boolean fallbackEnabled,
            Integer maxFallbacks, Integer circuitWindowSeconds, Integer circuitMinRequests,
            BigDecimal circuitFailureRate, Integer circuitOpenSeconds, Integer circuitHalfOpenProbes,
            Integer circuitHalfOpenSuccesses, boolean enabled, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public JdbcReliabilityPolicyRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcReliabilityPolicyRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcReliabilityPolicyRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, ReliabilityPolicyRow row) {
        DatabaseDialect d = dialect(connection);
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at"));
        int count = insertColumns.split(",").length;
        String sql = "INSERT INTO " + qualify(connection, "reliability_policy") + " (" + insertColumns + ", created_at, updated_at) "
                + "VALUES (" + inPlaceholders(count) + ", " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, row, d);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("可靠性策略写入失败", e);
        }
    }

    private void bind(PreparedStatement statement, ReliabilityPolicyRow row, DatabaseDialect d) throws SQLException {
        d.bindUuid(statement, 1, row.id());
        statement.setString(2, row.name());
        d.bindUuid(statement, 3, row.aliasId());
        statement.setInt(4, row.connectTimeoutMs());
        statement.setInt(5, row.firstTokenTimeoutMs());
        statement.setInt(6, row.totalTimeoutMs());
        statement.setInt(7, row.maxRetries());
        statement.setInt(8, row.maxCredentialFailovers());
        statement.setInt(9, row.initialBackoffMs());
        statement.setBigDecimal(10, row.backoffMultiplier());
        statement.setInt(11, row.jitterPercent());
        statement.setBoolean(12, row.respectRetryAfter());
        statement.setInt(13, row.maxRetryAfterMs());
        statement.setBoolean(14, row.fallbackEnabled());
        statement.setInt(15, row.maxFallbacks());
        statement.setInt(16, row.circuitWindowSeconds());
        statement.setInt(17, row.circuitMinRequests());
        statement.setBigDecimal(18, row.circuitFailureRate());
        statement.setInt(19, row.circuitOpenSeconds());
        statement.setInt(20, row.circuitHalfOpenProbes());
        statement.setInt(21, row.circuitHalfOpenSuccesses());
        statement.setBoolean(22, row.enabled());
        statement.setLong(23, row.version());
    }

    public Optional<ReliabilityPolicyRow> findLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "reliability_policy")
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("可靠性策略读取失败", e);
        }
    }

    public Optional<ReliabilityPolicyRow> lockLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "reliability_policy")
                + " WHERE id = ? AND deleted_at IS NULL " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("可靠性策略锁定失败", e);
        }
    }

    public Optional<ReliabilityPolicyRow> findEnabledConflict(Connection connection, UUID aliasId,
                                                              UUID exceptId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "reliability_policy")
                + " WHERE alias_id = ? AND enabled = true AND deleted_at IS NULL"
                + (exceptId == null ? "" : " AND id <> ?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, aliasId);
            if (exceptId != null) {
                d.bindUuid(statement, 2, exceptId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("可靠性冲突检查失败", e);
        }
    }

    public ReliabilityPolicyRow update(Connection connection, ReliabilityPolicyRow row) {
        DatabaseDialect d = dialect(connection);
        if (d.supportsReturning()) {
            String sql = """
                    UPDATE %s SET
                      name = ?, connect_timeout_ms = ?, first_token_timeout_ms = ?, total_timeout_ms = ?,
                      max_retries = ?, max_credential_failovers = ?, initial_backoff_ms = ?,
                      backoff_multiplier = ?, jitter_percent = ?, respect_retry_after = ?,
                      max_retry_after_ms = ?, fallback_enabled = ?, max_fallbacks = ?,
                      circuit_window_seconds = ?, circuit_min_requests = ?, circuit_failure_rate = ?,
                      circuit_open_seconds = ?, circuit_half_open_probes = ?,
                      circuit_half_open_successes = ?, enabled = ?, version = version + 1, updated_at = %s
                    WHERE id = ? AND deleted_at IS NULL
                    RETURNING %s
                    """.strip().formatted(qualify(connection, "reliability_policy"), d.nowFunction(), COLUMNS);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindUpdateParams(statement, row, d);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("可靠性策略更新未命中活行");
                    }
                    return mapRow(rs, d);
                }
            } catch (SQLException e) {
                throw translate("可靠性策略更新失败", e);
            }
        } else {
            String sql = "UPDATE " + qualify(connection, "reliability_policy") + " SET "
                    + "name = ?, connect_timeout_ms = ?, first_token_timeout_ms = ?, total_timeout_ms = ?, "
                    + "max_retries = ?, max_credential_failovers = ?, initial_backoff_ms = ?, "
                    + "backoff_multiplier = ?, jitter_percent = ?, respect_retry_after = ?, "
                    + "max_retry_after_ms = ?, fallback_enabled = ?, max_fallbacks = ?, "
                    + "circuit_window_seconds = ?, circuit_min_requests = ?, circuit_failure_rate = ?, "
                    + "circuit_open_seconds = ?, circuit_half_open_probes = ?, "
                    + "circuit_half_open_successes = ?, enabled = ?, version = version + 1, updated_at = " + d.nowFunction() + " "
                    + "WHERE id = ? AND deleted_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindUpdateParams(statement, row, d);
                int affected = statement.executeUpdate();
                if (affected == 0) {
                    throw new IllegalStateException("可靠性策略更新未命中活行");
                }
                return findLiveById(connection, row.id())
                        .orElseThrow(() -> new IllegalStateException("可靠性策略更新后未找到活行"));
            } catch (SQLException e) {
                throw translate("可靠性策略更新失败", e);
            }
        }
    }

    private void bindUpdateParams(PreparedStatement statement, ReliabilityPolicyRow row, DatabaseDialect d) throws SQLException {
        statement.setString(1, row.name());
        statement.setInt(2, row.connectTimeoutMs());
        statement.setInt(3, row.firstTokenTimeoutMs());
        statement.setInt(4, row.totalTimeoutMs());
        statement.setInt(5, row.maxRetries());
        statement.setInt(6, row.maxCredentialFailovers());
        statement.setInt(7, row.initialBackoffMs());
        statement.setBigDecimal(8, row.backoffMultiplier());
        statement.setInt(9, row.jitterPercent());
        statement.setBoolean(10, row.respectRetryAfter());
        statement.setInt(11, row.maxRetryAfterMs());
        statement.setBoolean(12, row.fallbackEnabled());
        statement.setInt(13, row.maxFallbacks());
        statement.setInt(14, row.circuitWindowSeconds());
        statement.setInt(15, row.circuitMinRequests());
        statement.setBigDecimal(16, row.circuitFailureRate());
        statement.setInt(17, row.circuitOpenSeconds());
        statement.setInt(18, row.circuitHalfOpenProbes());
        statement.setInt(19, row.circuitHalfOpenSuccesses());
        statement.setBoolean(20, row.enabled());
        d.bindUuid(statement, 21, row.id());
    }

    public void markDeleted(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "reliability_policy")
                + " SET deleted_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction() + ", "
                + "enabled = false WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("可靠性策略删除失败", e);
        }
    }

    public List<ReliabilityPolicyRow> list(Connection connection, String keyword, UUID aliasId,
                                           Boolean enabled, String sortExpression,
                                           int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualify(connection, "reliability_policy")).append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND ").append(d.ilikeClause("name"));
            params.add("%" + keyword.strip() + "%");
        }
        if (aliasId != null) {
            sql.append(" AND alias_id = ?");
            params.add(aliasId);
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        sql.append(" ORDER BY ").append(sortExpression).append(", id ASC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<ReliabilityPolicyRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs, d));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("可靠性策略列表查询失败", e);
        }
    }

    public long count(Connection connection, String keyword, UUID aliasId, Boolean enabled) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "reliability_policy")).append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND ").append(d.ilikeClause("name"));
            params.add("%" + keyword.strip() + "%");
        }
        if (aliasId != null) {
            sql.append(" AND alias_id = ?");
            params.add(aliasId);
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("可靠性策略计数失败", e);
        }
    }

    private ReliabilityPolicyRow mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new ReliabilityPolicyRow(
                d.readUuid(rs, "id"),
                rs.getString("name"),
                d.readUuid(rs, "alias_id"),
                rs.getInt("connect_timeout_ms"),
                rs.getInt("first_token_timeout_ms"),
                rs.getInt("total_timeout_ms"),
                rs.getInt("max_retries"),
                rs.getInt("max_credential_failovers"),
                rs.getInt("initial_backoff_ms"),
                rs.getBigDecimal("backoff_multiplier"),
                rs.getInt("jitter_percent"),
                rs.getBoolean("respect_retry_after"),
                rs.getInt("max_retry_after_ms"),
                rs.getBoolean("fallback_enabled"),
                rs.getInt("max_fallbacks"),
                rs.getInt("circuit_window_seconds"),
                rs.getInt("circuit_min_requests"),
                rs.getBigDecimal("circuit_failure_rate"),
                rs.getInt("circuit_open_seconds"),
                rs.getInt("circuit_half_open_probes"),
                rs.getInt("circuit_half_open_successes"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }
}
