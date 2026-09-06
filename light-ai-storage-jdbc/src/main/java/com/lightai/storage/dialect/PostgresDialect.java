package com.lightai.storage.dialect;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * PostgreSQL 专用方言实现。
 */
public final class PostgresDialect implements DatabaseDialect {

    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private PostgresDialect() {
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.POSTGRESQL;
    }

    @Override
    public String qualify(String schemaName, String tableName) {
        if (schemaName != null && !schemaName.isBlank()) {
            return schemaName + "." + tableName;
        }
        return tableName;
    }

    @Override
    public String jsonPlaceholder() {
        return "?::jsonb";
    }

    @Override
    public void bindUuid(PreparedStatement ps, int paramIndex, UUID value) throws SQLException {
        ps.setObject(paramIndex, value);
    }

    @Override
    public UUID readUuid(ResultSet rs, String columnLabel) throws SQLException {
        return rs.getObject(columnLabel, UUID.class);
    }

    @Override
    public UUID readUuid(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getObject(columnIndex, UUID.class);
    }

    @Override
    public void bindJson(PreparedStatement ps, int paramIndex, String json) throws SQLException {
        ps.setString(paramIndex, json);
    }

    @Override
    public String readJson(ResultSet rs, String columnLabel) throws SQLException {
        return rs.getString(columnLabel);
    }

    @Override
    public String readJson(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String nowFunction() {
        return "now()";
    }

    @Override
    public String intervalSecondsBeforeNow(int seconds) {
        return "now() - interval '" + seconds + " seconds'";
    }

    @Override
    public boolean supportsReturning() {
        return true;
    }

    @Override
    public boolean isUpsertInserted(int affectedRows, ResultSet rsIfReturning) throws SQLException {
        if (rsIfReturning != null) {
            return rsIfReturning.getBoolean("inserted");
        }
        return affectedRows == 1;
    }

    @Override
    public String forUpdateSkipLockedClause() {
        return "FOR UPDATE SKIP LOCKED";
    }

    @Override
    public String ilikeClause(String column) {
        return column + " ILIKE ?";
    }

    @Override
    public boolean supportsArrayType() {
        return true;
    }
}

