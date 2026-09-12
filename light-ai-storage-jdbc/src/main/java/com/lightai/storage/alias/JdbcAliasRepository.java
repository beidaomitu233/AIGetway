package com.lightai.storage.alias;

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
 * virtual_model JDBC 仓储（DATABASE_PLAN §6）。
 * V5 起物理表 model_alias 更名为 virtual_model、业务码列 alias 更名为 code（id 不变，
 * 历史/快照 JSON 的 alias 键不受影响）；status 列与 enabled 同语句双写，读取仍以 enabled 为准。
 * code 活行全局唯一；删除前引用检查由服务层完成。
 * 支持 PostgreSQL 与 MySQL 8.0 / H2(MySQL 模式) 双方言自适应。
 */
public class JdbcAliasRepository extends AbstractJdbcRepository {

    private static final String COLUMNS =
            "id, code, display_name, description, route_strategy, enabled, version, created_at, updated_at";

    public JdbcAliasRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcAliasRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcAliasRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** status 与 enabled 同源：ACTIVE/DISABLED。 */
    static String statusOf(boolean enabled) {
        return enabled ? "ACTIVE" : "DISABLED";
    }

    public void insert(Connection connection, AliasRecord record) {
        DatabaseDialect d = dialect(connection);
        String insertColumns = COLUMNS.substring(0, COLUMNS.lastIndexOf(", created_at")) + ", status";
        int columnCount = insertColumns.split(",").length;
        String sql = "INSERT INTO " + qualify(connection, "virtual_model") + " (" + insertColumns + ", created_at, updated_at) "
                + "VALUES (" + inPlaceholders(columnCount) + ", " + d.nowFunction() + ", " + d.nowFunction() + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, record.id());
            statement.setString(2, record.alias());
            statement.setString(3, record.displayName());
            statement.setString(4, record.description());
            statement.setString(5, record.routeStrategy());
            statement.setBoolean(6, record.enabled());
            statement.setLong(7, record.version());
            statement.setString(8, statusOf(record.enabled()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("Alias写入失败", e);
        }
    }

    public Optional<AliasRecord> findLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "virtual_model")
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("Alias读取失败", e);
        }
    }

    public Optional<AliasRecord> lockLiveById(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "virtual_model")
                + " WHERE id = ? AND deleted_at IS NULL " + d.forUpdateClause();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("Alias锁定失败", e);
        }
    }

    public boolean existsByLiveAlias(Connection connection, String alias) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT 1 FROM " + qualify(connection, "virtual_model") + " WHERE code = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, alias);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw translate("Alias唯一性检查失败", e);
        }
    }

    public AliasRecord update(Connection connection, AliasRecord record) {
        DatabaseDialect d = dialect(connection);
        if (d.supportsReturning()) {
            String sql = """
                    UPDATE %s
                       SET display_name = ?, description = ?, enabled = ?, status = ?,
                           version = version + 1, updated_at = %s
                     WHERE id = ? AND deleted_at IS NULL
                    RETURNING %s
                    """.strip().formatted(qualify(connection, "virtual_model"), d.nowFunction(), COLUMNS);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.displayName());
                statement.setString(2, record.description());
                statement.setBoolean(3, record.enabled());
                statement.setString(4, statusOf(record.enabled()));
                d.bindUuid(statement, 5, record.id());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("Alias更新未命中活行");
                    }
                    return mapRow(rs, d);
                }
            } catch (SQLException e) {
                throw translate("Alias更新失败", e);
            }
        } else {
            String sql = "UPDATE " + qualify(connection, "virtual_model")
                    + " SET display_name = ?, description = ?, enabled = ?, status = ?, version = version + 1, updated_at = " + d.nowFunction()
                    + " WHERE id = ? AND deleted_at IS NULL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.displayName());
                statement.setString(2, record.description());
                statement.setBoolean(3, record.enabled());
                statement.setString(4, statusOf(record.enabled()));
                d.bindUuid(statement, 5, record.id());
                int affected = statement.executeUpdate();
                if (affected == 0) {
                    throw new IllegalStateException("Alias更新未命中活行");
                }
                return findLiveById(connection, record.id())
                        .orElseThrow(() -> new IllegalStateException("Alias更新后未找到活行"));
            } catch (SQLException e) {
                throw translate("Alias更新失败", e);
            }
        }
    }

    public void markDeleted(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "UPDATE " + qualify(connection, "virtual_model")
                + " SET deleted_at = " + d.nowFunction() + ", updated_at = " + d.nowFunction()
                + " WHERE id = ? AND deleted_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("Alias删除失败", e);
        }
    }

    public List<AliasRecord> list(Connection connection, String keyword, Boolean enabled,
                                  String sortExpression, int limit, int offset) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualify(connection, "virtual_model")).append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (").append(d.ilikeClause("code"))
                    .append(" OR ").append(d.ilikeClause("display_name")).append(")");
            params.add("%" + keyword.strip() + "%");
            params.add("%" + keyword.strip() + "%");
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        // 服务层排序白名单与 API sort 键仍为 alias（BE-P21 DTO 切换前），物理列已更名 code。
        sql.append(" ORDER BY ").append(sortExpression.replaceFirst("(?i)\\balias\\b", "code"))
                .append(", id ASC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, params, d);
            statement.setInt(params.size() + 1, limit);
            statement.setInt(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<AliasRecord> records = new ArrayList<>();
                while (rs.next()) {
                    records.add(mapRow(rs, d));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw translate("Alias列表查询失败", e);
        }
    }

    public long count(Connection connection, String keyword, Boolean enabled) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
                .append(qualify(connection, "virtual_model")).append(" WHERE deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (").append(d.ilikeClause("code"))
                    .append(" OR ").append(d.ilikeClause("display_name")).append(")");
            params.add("%" + keyword.strip() + "%");
            params.add("%" + keyword.strip() + "%");
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
            throw translate("Alias计数失败", e);
        }
    }

    private AliasRecord mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new AliasRecord(
                d.readUuid(rs, "id"),
                rs.getString("code"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("route_strategy"),
                rs.getBoolean("enabled"),
                rs.getLong("version"),
                d.readOffsetDateTime(rs, "created_at"),
                d.readOffsetDateTime(rs, "updated_at"));
    }
}
