package com.lightai.storage.audit;

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

/** audit_log 查询 JDBC 实现（DATABASE_PLAN §38）：I(created_at desc,id)、I(request_id)。 */
public final class JdbcAuditQueryRepository extends AbstractJdbcRepository implements AuditQueryRepository {

    private static final String COLUMNS = """
            id, created_at, request_id, operator_id, action, entity_type, entity_id, result,
            changes, error_code, error_summary, source_mode, source_ip_masked""";

    public JdbcAuditQueryRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcAuditQueryRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcAuditQueryRepository() {
        super();
    }

    @Override
    public List<AuditQueryRow> list(Connection connection, String filterSql, List<Object> filterValues,
                                    String orderSql, long offset, int limit) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "audit_log")
                + " WHERE 1=1" + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql)
                + " ORDER BY " + orderSql + " LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, filterValues, d);
            int i = filterValues.size() + 1;
            statement.setInt(i++, limit);
            statement.setLong(i, offset);
            return mapList(statement, d);
        } catch (SQLException e) {
            throw new IllegalStateException("audit_log 列表读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long count(Connection connection, String filterSql, List<Object> filterValues) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT count(*) FROM " + qualify(connection, "audit_log")
                + " WHERE 1=1" + (filterSql == null || filterSql.isBlank() ? "" : " AND " + filterSql);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, filterValues, d);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("audit_log 计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public Optional<AuditQueryRow> find(Connection connection, UUID id) {
        DatabaseDialect d = dialect(connection);
        String sql = "SELECT " + COLUMNS + " FROM " + qualify(connection, "audit_log") + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            d.bindUuid(statement, 1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs, d)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("audit_log 详情读取失败：" + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public long countAll(Connection connection) {
        String sql = "SELECT count(*) FROM " + qualify(connection, "audit_log");
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("audit_log 计数失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private List<AuditQueryRow> mapList(PreparedStatement statement, DatabaseDialect d) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            List<AuditQueryRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapRow(rs, d));
            }
            return rows;
        }
    }

    private static AuditQueryRow mapRow(ResultSet rs, DatabaseDialect d) throws SQLException {
        return new AuditQueryRow(
                d.readUuid(rs, "id"),
                d.readOffsetDateTime(rs, "created_at"),
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
}
