package com.lightai.storage.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** audit_log 查询 JDBC 实现（DATABASE_PLAN §38）：I(created_at desc,id)、I(request_id)。 */
public final class JdbcAuditQueryRepository implements AuditQueryRepository {

    private static final String COLUMNS = """
            id, created_at, request_id, operator_id, action, entity_type, entity_id, result,
            changes, error_code, error_summary, source_mode, source_ip_masked""";

    private final String schemaName;

    public JdbcAuditQueryRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcAuditQueryRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    @Override
    public List<AuditQueryRow> list(Connection connection, String filterSql, List<Object> filterValues,
                                    String orderSql, long offset, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified()
                + " WHERE 1=1" + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql)
                + " ORDER BY " + orderSql + " OFFSET ? LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            for (Object value : filterValues) {
                statement.setObject(i++, value);
            }
            statement.setLong(i++, offset);
            statement.setInt(i, limit);
            return mapList(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("audit_log 列表读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long count(Connection connection, String filterSql, List<Object> filterValues) {
        String sql = "SELECT count(*) FROM " + qualified()
                + " WHERE 1=1" + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            for (Object value : filterValues) {
                statement.setObject(i++, value);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("audit_log 计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<AuditQueryRow> find(Connection connection, UUID id) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("audit_log 详情读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long countAll(Connection connection) {
        String sql = "SELECT count(*) FROM " + qualified();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("audit_log 计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private List<AuditQueryRow> mapList(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            List<AuditQueryRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapRow(rs));
            }
            return rows;
        }
    }

    private static AuditQueryRow mapRow(ResultSet rs) throws SQLException {
        return new AuditQueryRow(
                rs.getObject("id", UUID.class),
                offset(rs.getTimestamp("created_at")),
                rs.getString("request_id"),
                rs.getString("operator_id"),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("result"),
                rs.getString("changes"),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                rs.getString("source_mode"),
                rs.getString("source_ip_masked"));
    }

    private String qualified() {
        return schemaName + ".audit_log";
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp == null ? null : OffsetDateTime.ofInstant(timestamp.toInstant(), java.time.ZoneOffset.UTC);
    }
}
