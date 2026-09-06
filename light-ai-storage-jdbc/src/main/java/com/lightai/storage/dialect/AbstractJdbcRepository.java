package com.lightai.storage.dialect;

import java.sql.Connection;

/**
 * JDBC 仓储抽象基类：提供方言解析与表名修饰能力。
 */
public abstract class AbstractJdbcRepository {

    protected final String schemaName;
    protected final DatabaseDialect explicitDialect;

    protected AbstractJdbcRepository(String schemaName, DatabaseDialect explicitDialect) {
        this.schemaName = schemaName;
        this.explicitDialect = explicitDialect;
    }

    protected AbstractJdbcRepository(String schemaName) {
        this(schemaName, null);
    }

    protected AbstractJdbcRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME, null);
    }

    protected DatabaseDialect dialect(Connection connection) {
        if (explicitDialect != null) {
            return explicitDialect;
        }
        return DialectResolver.resolve(connection);
    }

    protected String qualify(Connection connection, String tableName) {
        return dialect(connection).qualify(schemaName, tableName);
    }

    protected void bindParameter(java.sql.PreparedStatement ps, int index, Object val, DatabaseDialect d) throws java.sql.SQLException {
        if (val instanceof java.util.UUID u) {
            d.bindUuid(ps, index, u);
        } else if (val instanceof Boolean b) {
            ps.setBoolean(index, b);
        } else {
            ps.setObject(index, val);
        }
    }

    protected void bindParameters(java.sql.PreparedStatement ps, java.util.List<Object> params, DatabaseDialect d) throws java.sql.SQLException {
        for (int i = 0; i < params.size(); i++) {
            bindParameter(ps, i + 1, params.get(i), d);
        }
    }

    protected static String inPlaceholders(int count) {
        if (count <= 0) {
            return "";
        }
        return count == 1 ? "?" : ("?,".repeat(count - 1) + "?");
    }

    protected static Long getLongOrNull(java.sql.ResultSet rs, String columnLabel) throws java.sql.SQLException {
        Object val = rs.getObject(columnLabel);
        return val == null ? null : ((Number) val).longValue();
    }

    protected static Integer getIntOrNull(java.sql.ResultSet rs, String columnLabel) throws java.sql.SQLException {
        Object val = rs.getObject(columnLabel);
        return val == null ? null : ((Number) val).intValue();
    }

    protected static IllegalStateException translate(String message, java.sql.SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if ("23505".equals(state) || "23000".equals(state) || e.getErrorCode() == 1062) {
            return new IllegalStateException("UNIQUE_VIOLATION: " + message, e);
        }
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}

