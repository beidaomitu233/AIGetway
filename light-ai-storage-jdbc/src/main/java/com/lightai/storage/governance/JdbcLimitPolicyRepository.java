package com.lightai.storage.governance;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;

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
 * limit_policy JDBC 仓储（DATABASE_PLAN §8）。
 * 同一 scope_type+scope_id 至多一条启用（服务层保存与启用两阶段校验，
 * 数据库部分唯一索引兜底）。
 * 支持 PostgreSQL 与 MySQL 5.7 / 8.0 双方言自适应。
 */
public class JdbcLimitPolicyRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, name, scope_type, scope_id, rpm_limit, tpm_limit, concurrent_limit, "
                    + "overflow_strategy, queue_timeout_ms, queue_max_size, enabled, version, "
                    + "created_at, updated_at";

    public record LimitPolicyRow(
            UUID id, String name, String scopeType, UUID scopeId, Long rpmLimit, Long tpmLimit,
            Integer concurrentLimit, String overflowStrategy, Integer queueTimeoutMs,
            Integer queueMaxSize, boolean enabled, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public JdbcLimitPolicyRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcLimitPolicyRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcLimitPolicyRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, LimitPolicyRow row) {
        DatabaseDialect d = dialect(connection);
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at"));
        int count = insertColumns.split(",").length;
        String sql = "INSERT INTO " + qualify(connection, "limit_policy") + " (" + insertColumns + ", created_at, updated_at) "
                + "VALUES (" + inPlaceholders(count) + ", " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, row, d);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("限流策略写入失败", e);
        }
    }

    private void bind(PreparedStatement statement, LimitPolicyRow row, DatabaseDialect d) throws SQLException {
        d.bindUuid(statement, 1, row.id());
        statement.setString(2, row.name());
        statement.setString(3, row.scopeType());
        d.bindUuid(statement, 4, row.scopeId());
        statement.setObject(5, row.rpmLimit());
        statement.setObject(6, row.tpmLimit());
        statement.setObject(7, row.concurrentLimit());
        statement.setString(8, row.overflowStrategy());
        statement.setObject(9, row.queueTimeoutMs());
        statement.setObject(10, row.queueMaxSize());
        statement.setBoolean(11, row.enabled());
        statement.setLong(12, row.version());
    }

    public Optional<LimitPolicyRow> findLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "limit_policy")
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("限流策略读取失败", e);
        }
    }

    public Optional<LimitPolicyRow> lockLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "limit_policy")
                + " WHERE id = ? AND deleted_at IS NULL " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("限流策略锁定失败", e);
        }
    }

    /** 同 scope 启用冲突检查（排除自身）。 */
    public Optional<LimitPolicyRow> findEnabledConflict(Connection connection, String scopeType,
                                                        UUID scopeId, UUID exceptId) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "limit_policy")
                + " WHERE scope_type = ? AND scope_id = ? AND enabled = true AND deleted_at IS NULL"
                + (exceptId == null ? "" : " AND id <> ?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scopeType);
            d.bindUuid(statement, 2, scopeId);
            if (exceptId != null) {
                d.bindUuid(statement, 3, exceptId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("限流冲突检查失败", e);
        }
    }

    public LimitPolicyRow update(Connection connection, LimitPolicyRow row) {
        DatabaseDialect d = dialect(connection);
        if (d.supportsReturning()) {
            String sql = """
                    UPDATE %s
                       SET name = ?, rpm_limit = ?, tpm_limit = ?, concurrent_limit = ?,
                           overflow_strategy = ?, queue_timeout_ms = ?, queue_max_size = ?,
                           enabled = ?, version = version + 1, updated_at = %s
                     WHERE id = ? AND deleted_at IS NULL
                    RETURNING %s
                    """.strip().formatted(qualify(connection, "limit_policy"), d.nowFunction(), COLUMNS);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, row.name());
                statement.setObject(2, row.rpmLimit());
                statement.setObject(3, row.tpmLimit());
                statement.setObject(4, row.concurrentLimit());
                statement.setString(5, row.overflowStrategy());
                statement.setObject(6, row.queueTimeoutMs());
                statement.setObject(7, row.queueMaxSize());
                statement.setBoolean(8, row.enabled());
                d.bindUuid(statement, 9, row.id());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("限流策略更新未命中活行");
                    }
                    return mapRow(rs, d);
                }
            } catch (SQLException e) {
                throw translate("限流策略更新失败", e);
            }
        } else {
            String sql = "UPDATE " + qualify(connection, "limit_policy")
                    + " SET name = ?, rpm_limit = ?, tpm_limit = ?, concurrent_limit = ?, "
                    + "overflow_strategy = ?, queue_timeout_ms = ?, queue_max_size = ?, "
                    + "enabled = ?, version = version + 1, updated_at = " + d.nowFunction()
                    + " WHERE id = ? AND deleted_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, row.name());
                statement.setObject(2, row.rpmLimit());
                statement.setObject(3, row.tpmLimit());
                statement.setObject(4, row.concurrentLimit());
                statement.setString(5, row.overflowStrategy());
                statement.setObject(6, row.queueTimeoutMs());
                statement.setObject(7, row.queueMaxSize());
                statement.setBoolean(8, row.enabled());
                d.bindUuid(statement, 9, row.id());
                int affected = statement.executeUpdate();
                if (affected == 0) {
                    throw new IllegalStateException("限流策略更新未命中活行");
                }
                return findLiveById(connection, row.id())
                        .orElseThrow(() -> new IllegalStateException("限流策略更新后未找到活行"));
            } catch (SQLException e) {
                throw translate("限流策略更新失败", e);
            }
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "limit_policy")
                + " SET deleted_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction() + ", "
                + "enabled = false WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("限流策略删除失败", e);
        }
    }

    public List<LimitPolicyRow> list(Connection connection, String keyword, String scopeType,
                                     Boolean enabled, String sortExpression, int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualify(connection, "limit_policy")).append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND ").append(d.ilikeClause("name"));
            params.add("%" + keyword.strip() + "%");
        }
        if (scopeType != null && !scopeType.isBlank()) {
            sql.append(" AND scope_type = ?");
            params.add(scopeType.strip());
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
                List<LimitPolicyRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs, d));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("限流策略列表查询失败", e);
        }
    }

    public long count(Connection connection, String keyword, String scopeType, Boolean enabled) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "limit_policy")).append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND ").append(d.ilikeClause("name"));
            params.add("%" + keyword.strip() + "%");
        }
        if (scopeType != null && !scopeType.isBlank()) {
            sql.append(" AND scope_type = ?");
            params.add(scopeType.strip());
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
            throw translate("限流策略计数失败", e);
        }
    }

    private LimitPolicyRow mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new LimitPolicyRow(
                d.readUuid(rs, "id"),
                rs.getString("name"),
                rs.getString("scope_type"),
                d.readUuid(rs, "scope_id"),
                getLongOrNull(rs, "rpm_limit"),
                getLongOrNull(rs, "tpm_limit"),
                getIntOrNull(rs, "concurrent_limit"),
                rs.getString("overflow_strategy"),
                getIntOrNull(rs, "queue_timeout_ms"),
                getIntOrNull(rs, "queue_max_size"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }
}
