package com.lightai.storage.pool;

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
 * credential_pool JDBC 仓储（DATABASE_PLAN §2）。
 * 活行唯一约束 (provider_id, name)；软删除置 deleted_at。
 */
public final class JdbcPoolRepository {

    private static final String COLUMNS =
            "id, provider_id, name, selection_strategy, enabled, version, created_at, updated_at";

    private final String schemaName;

    public JdbcPoolRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcPoolRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public void insert(Connection connection, PoolRecord record) {
        String sql = "INSERT INTO %s.credential_pool (%s) VALUES (?, ?, ?, ?, ?, ?, now(), now())"
                .formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, record.id());
            statement.setObject(2, record.providerId());
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
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("凭证池读取失败", e);
        }
    }

    public Optional<PoolRecord> lockLiveById(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE id = ? AND deleted_at IS NULL FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("凭证池锁定失败", e);
        }
    }

    public boolean existsByLiveNameInProvider(Connection connection, UUID providerId, String name) {
        String sql = "SELECT 1 FROM " + qualified()
                + " WHERE provider_id = ? AND name = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, providerId);
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("凭证池名称检查失败", e);
        }
    }

    public PoolRecord update(Connection connection, PoolRecord record) {
        String sql = """
                UPDATE %s.credential_pool
                   SET name = ?, selection_strategy = ?, enabled = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                RETURNING %s
                """.strip().formatted(qualified(), COLUMNS);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.name());
            statement.setString(2, record.selectionStrategy());
            statement.setBoolean(3, record.enabled());
            statement.setObject(4, record.id());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("凭证池更新未命中活行");
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw translate("凭证池更新失败", e);
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        String sql = "UPDATE %s.credential_pool SET deleted_at = now(), updated_at = now() "
                + "WHERE id = ? AND deleted_at IS NULL".formatted(qualified());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
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
        String sql = """
                SELECT p.id, p.provider_id, p.name, p.selection_strategy, p.enabled,
                       p.version, p.created_at, p.updated_at, pr.name AS provider_name
                  FROM %s p
                  JOIN %s.provider pr ON pr.id = p.provider_id AND pr.deleted_at IS NULL
                 WHERE p.deleted_at IS NULL
                """.strip().formatted(qualified(), schemaName);
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (filter != null) {
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                where.append(" AND p.name ILIKE ?");
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
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<PoolRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new PoolRow(mapRow(rs), rs.getString("provider_name")));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("凭证池列表查询失败", e);
        }
    }

    public long count(Connection connection, PoolFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualified())
                .append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (filter != null) {
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                sql.append(" AND name ILIKE ?");
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
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
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

    private PoolRecord mapRow(ResultSet rs) throws SQLException {
        return new PoolRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("provider_id", UUID.class),
                rs.getString("name"),
                rs.getString("selection_strategy"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String qualified() {
        return schemaName + ".credential_pool";
    }

    private static IllegalStateException translate(String message, SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if ("23505".equals(state)) {
            return new IllegalStateException("UNIQUE_VIOLATION: " + message, e);
        }
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
