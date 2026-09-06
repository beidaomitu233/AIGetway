package com.lightai.storage.dialect;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * MySQL 通用方言实现：同时兼容 MySQL 5.7 及 MySQL 8.0+。
 */
public final class MySqlDialect implements DatabaseDialect {

    public static final MySqlDialect INSTANCE = new MySqlDialect();

    private MySqlDialect() {
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.MYSQL;
    }

    @Override
    public String qualify(String schemaName, String tableName) {
        // MySQL 使用 Database（Catalog）而非独立 Schema。
        // 若 schemaName 为空或为默认的 light_ai，直接使用当前数据库的反引号表名；
        // 若明确配置了非默认 catalog/schema，拼接 `db`.`table`。
        if (schemaName == null || schemaName.isBlank() || "light_ai".equalsIgnoreCase(schemaName)) {
            return "`" + tableName + "`";
        }
        return "`" + schemaName + "`.`" + tableName + "`";
    }

    @Override
    public String jsonPlaceholder() {
        return "?";
    }

    @Override
    public void bindUuid(PreparedStatement ps, int paramIndex, UUID value) throws SQLException {
        ps.setString(paramIndex, value != null ? value.toString() : null);
    }

    @Override
    public UUID readUuid(ResultSet rs, String columnLabel) throws SQLException {
        String val = rs.getString(columnLabel);
        return val != null ? UUID.fromString(val) : null;
    }

    @Override
    public UUID readUuid(ResultSet rs, int columnIndex) throws SQLException {
        String val = rs.getString(columnIndex);
        return val != null ? UUID.fromString(val) : null;
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
        return "now(6)";
    }

    @Override
    public String intervalSecondsBeforeNow(int seconds) {
        return "DATE_SUB(now(6), INTERVAL " + seconds + " SECOND)";
    }

    @Override
    public boolean supportsReturning() {
        return false;
    }

    @Override
    public boolean isUpsertInserted(int affectedRows, ResultSet rsIfReturning) throws SQLException {
        // MySQL 原生 INSERT ... ON DUPLICATE KEY UPDATE：
        // 1 行受影响表示全新插入；2 行受影响表示命中既有键并更新；0 表示已有且无列数据变更。
        return affectedRows == 1;
    }

    @Override
    public String forUpdateSkipLockedClause() {
        // MySQL 5.7 不支持 SKIP LOCKED（8.0+ 引入）；
        // 保持 FOR UPDATE 基础行锁以确保 5.7 语法通过与争抢排他。
        return "FOR UPDATE";
    }

    @Override
    public String ilikeClause(String column) {
        // MySQL 5.7 / 8.0 不支持 ILIKE 关键字；使用 LOWER(col) LIKE LOWER(?) 实现大小写不敏感匹配
        return "LOWER(" + column + ") LIKE LOWER(?)";
    }

    @Override
    public boolean supportsArrayType() {
        return false;
    }
}

