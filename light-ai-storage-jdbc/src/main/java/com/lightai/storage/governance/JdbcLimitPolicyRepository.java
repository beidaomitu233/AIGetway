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
 * limit_policy JDBC 仓储（DATABASE_PLAN §8）。
 * 同一 scope_type+scope_id 至多一条启用（服务层保存与启用两阶段校验，
 * 数据库部分唯一索引兜底）。
 */
public class JdbcLimitPolicyRepository {

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

    private final String schemaName;

    public JdbcLimitPolicyRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcLimitPolicyRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, LimitPolicyRow row) {
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at"));
        String sql = "INSERT INTO %s.limit_policy (%s, created_at, updated_at) VALUES (%s, now(), now())"
                .formatted(qualified(), insertColumns, placeholders(insertColumns));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, row);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("限流策略写入失败", e);
        }
    }

    private void bind(PreparedStatement statement, LimitPolicyRow row) throws SQLException {
        statement.setObject(1, row.id());
        statement.setString(2, row.name());
        statement.setString(3, row.scopeType());
        statement.setObject(4, row.scopeId());
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
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("限流策略读取失败", e);
        }
    }

    public Optional<LimitPolicyRow> lockLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE id = ? AND deleted_at IS NULL FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("限流策略锁定失败", e);
        }
    }

    /** 同 scope 启用冲突检查（排除自身）。 */
    public Optional<LimitPolicyRow> findEnabledConflict(Connection connection, String scopeType,
                                                        UUID scopeId, UUID exceptId) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE scope_type = ? AND scope_id = ? AND enabled AND deleted_at IS NULL"
                + (exceptId == null ? "" : " AND id <> ?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scopeType);
            statement.setObject(2, scopeId);
            if (exceptId != null) {
                statement.setObject(3, exceptId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("限流冲突检查失败", e);
        }
    }

    public LimitPolicyRow update(Connection connection, LimitPolicyRow row) {
        String sql = """
                UPDATE %s.limit_policy
                   SET name = ?, rpm_limit = ?, tpm_limit = ?, concurrent_limit = ?,
                       overflow_strategy = ?, queue_timeout_ms = ?, queue_max_size = ?,
                       enabled = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                RETURNING %s
                """.strip().formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, row.name());
            statement.setObject(2, row.rpmLimit());
            statement.setObject(3, row.tpmLimit());
            statement.setObject(4, row.concurrentLimit());
            statement.setString(5, row.overflowStrategy());
            statement.setObject(6, row.queueTimeoutMs());
            statement.setObject(7, row.queueMaxSize());
            statement.setBoolean(8, row.enabled());
            statement.setObject(9, row.id());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("限流策略更新未命中活行");
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw translate("限流策略更新失败", e);
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        String sql = "UPDATE %s.limit_policy SET deleted_at = now(), updated_at = now(), "
                + "enabled = false WHERE id = ? AND deleted_at IS NULL".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("限流策略删除失败", e);
        }
    }

    public List<LimitPolicyRow> list(Connection connection, String keyword, String scopeType,
                                     Boolean enabled, String sortExpression, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualified()).append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND name ILIKE ?");
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
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<LimitPolicyRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("限流策略列表查询失败", e);
        }
    }

    public long count(Connection connection, String keyword, String scopeType, Boolean enabled) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualified())
                .append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND name ILIKE ?");
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
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("限流策略计数失败", e);
        }
    }

    private LimitPolicyRow mapRow(ResultSet rs) throws SQLException {
        return new LimitPolicyRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("scope_type"),
                rs.getObject("scope_id", UUID.class),
                (Long) rs.getObject("rpm_limit"),
                (Long) rs.getObject("tpm_limit"),
                (Integer) rs.getObject("concurrent_limit"),
                rs.getString("overflow_strategy"),
                (Integer) rs.getObject("queue_timeout_ms"),
                (Integer) rs.getObject("queue_max_size"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String placeholders(String columns) {
        int count = columns.split(",").length;
        return "(" + "?,".repeat(count - 1) + "?)";
    }

    private String qualified() {
        return schemaName + ".limit_policy";
    }

    protected static IllegalStateException translate(String message, SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if ("23505".equals(state)) {
            return new IllegalStateException("UNIQUE_VIOLATION: " + message, e);
        }
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
