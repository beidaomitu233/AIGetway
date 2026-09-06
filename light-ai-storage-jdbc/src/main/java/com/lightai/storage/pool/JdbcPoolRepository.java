package com.lightai.storage.pool;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * credential_pool JDBC 仓储（DATABASE_PLAN §2）。
 * 活行唯一约束 (provider_id, name)；软删除置 deleted_at。
 * 支持 PostgreSQL 与 MySQL 5.7 / 8.0 双方言自适应。
 */
public final class JdbcPoolRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, provider_id, name, selection_strategy, enabled, version, created_at, updated_at";

    public JdbcPoolRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcPoolRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcPoolRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, PoolRecord record) {
        DatabaseDialect d = dialect(connection);
        String sql = "INSERT INTO " + qualify(connection, "credential_pool") + " (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, record.id());
            d.bindUuid(statement, 2, record.providerId());
            statement.setString(3, record.name());
            statement.setString(4, record.selectionStrategy());
            statement.setBoolean(5, record.enabled());
            statement.setLong(6, record.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("凭证池写入失败", e);
        }
    }

    public Optional<PoolRecord> findLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "credential_pool")
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("凭证池读取失败", e);
        }
    }

    public Optional<PoolRecord> lockLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "credential_pool")
                + " WHERE id = ? AND deleted_at IS NULL " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("凭证池锁定失败", e);
        }
    }

    public boolean existsByLiveNameInProvider(Connection connection, UUID providerId, String name) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "credential_pool")
                + " WHERE provider_id = ? AND name = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, providerId);
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("凭证池名称检查失败", e);
        }
    }

    public PoolRecord update(Connection connection, PoolRecord record) {
        DatabaseDialect d = dialect(connection);
        if (d.supportsReturning()) {
            String sql = """
                    UPDATE %s
                       SET name = ?, selection_strategy = ?, enabled = ?,
                           version = version + 1, updated_at = %s
                     WHERE id = ? AND deleted_at IS NULL
                    RETURNING %s
                    """.strip().formatted(qualify(connection, "credential_pool"), d.nowFunction(), COLUMNS);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.name());
                statement.setString(2, record.selectionStrategy());
                statement.setBoolean(3, record.enabled());
                d.bindUuid(statement, 4, record.id());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("凭证池更新未命中活行");
                    }
                    return mapRow(rs, d);
                }
            } catch (SQLException e) {
                throw translate("凭证池更新失败", e);
            }
        } else {
            String sql = "UPDATE " + qualify(connection, "credential_pool")
                    + " SET name = ?, selection_strategy = ?, enabled = ?, version = version + 1, updated_at = " + d.nowFunction()
                    + " WHERE id = ? AND deleted_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.name());
                statement.setString(2, record.selectionStrategy());
                statement.setBoolean(3, record.enabled());
                d.bindUuid(statement, 4, record.id());
                int affected = statement.executeUpdate();
                if (affected == 0) {
                    throw new IllegalStateException("凭证池更新未命中活行");
                }
                return findLiveById(connection, record.id())
                        .orElseThrow(() -> new IllegalStateException("凭证池更新后未找到活行"));
            } catch (SQLException e) {
                throw translate("凭证池更新失败", e);
            }
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "credential_pool")
                + " SET deleted_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("凭证池删除失败", e);
        }
    }

    /** 列表行：池 + 所属 Provider 名称。 */
    public record PoolRow(PoolRecord pool, String providerName) {
    }

    public List<PoolRow> listRows(Connection connection, PoolFilter filter,
                                  String sortExpression, int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        String sql = """
                SELECT p.id, p.provider_id, p.name, p.selection_strategy, p.enabled,
                       p.version, p.created_at, p.updated_at, pr.name AS provider_name
                  FROM %s p
                  JOIN %s pr ON pr.id = p.provider_id AND pr.deleted_at IS NULL
                 WHERE p.deleted_at IS NULL
                """.strip().formatted(qualify(connection, "credential_pool"), qualify(connection, "provider"));
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (filter != null) {
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                where.append(" AND ").append(d.ilikeClause("p.name"));
                params.add("%" + filter.keyword().strip() + "%");
            }
            if (filter.providerId() != null) {
                where.append(" AND p.provider_id = ?");
                params.add(filter.providerId());
            }
            if (filter.enabled() != null) {
                where.append(" AND p.enabled = ?");
                params.add(filter.enabled());
            }
        }
        sql += where + " ORDER BY p." + sortExpression + ", p.id ASC LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, params, d);
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<PoolRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new PoolRow(mapRow(rs, d), rs.getString("provider_name")));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("凭证池列表查询失败", e);
        }
    }

    public long count(Connection connection, PoolFilter filter) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "credential_pool"))
                .append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (filter != null) {
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                sql.append(" AND ").append(d.ilikeClause("name"));
                params.add("%" + filter.keyword().strip() + "%");
            }
            if (filter.providerId() != null) {
                sql.append(" AND provider_id = ?");
                params.add(filter.providerId());
            }
            if (filter.enabled() != null) {
                sql.append(" AND enabled = ?");
                params.add(filter.enabled());
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("凭证池计数失败", e);
        }
    }

    public record PoolFilter(String keyword, UUID providerId, Boolean enabled) {
    }

    private PoolRecord mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new PoolRecord(
                d.readUuid(rs, "id"),
                d.readUuid(rs, "provider_id"),
                rs.getString("name"),
                rs.getString("selection_strategy"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }
}
