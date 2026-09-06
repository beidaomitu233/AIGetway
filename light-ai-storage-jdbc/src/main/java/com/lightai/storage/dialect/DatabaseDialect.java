package com.lightai.storage.dialect;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 数据库方言抽象接口：抹平 PostgreSQL、MySQL 8.0 与 MySQL 5.7 之间的语法与类型差异。
 */
public interface DatabaseDialect {

    /** 获取方言类型枚举。 */
    DatabaseType databaseType();

    /**
     * 修饰表名。
     * PostgreSQL: "schema.table"
     * MySQL: "`table`" 或 "`schema`.`table`"
     */
    String qualify(String schemaName, String tableName);

    /**
     * JSON 字段占位符。
     * PostgreSQL: "?::jsonb"
     * MySQL: "?"
     */
    String jsonPlaceholder();

    /** 绑定 UUID 参数。 */
    void bindUuid(PreparedStatement ps, int paramIndex, UUID value) throws SQLException;

    /** 从结果集中按列名读取 UUID。 */
    UUID readUuid(ResultSet rs, String columnLabel) throws SQLException;

    /** 从结果集中按索引读取 UUID。 */
    UUID readUuid(ResultSet rs, int columnIndex) throws SQLException;

    /** 绑定 JSON 字符串参数。 */
    void bindJson(PreparedStatement ps, int paramIndex, String json) throws SQLException;

    /** 从结果集中按列名读取 JSON 字符串。 */
    String readJson(ResultSet rs, String columnLabel) throws SQLException;

    /** 从结果集中按索引读取 JSON 字符串。 */
    String readJson(ResultSet rs, int columnIndex) throws SQLException;

    /**
     * 获取当前时间函数。
     * PostgreSQL: "now()"
     * MySQL: "now(6)"
     */
    String nowFunction();

    /**
     * 相对当前时间往前推移秒数的表达式。
     * PostgreSQL: "now() - interval 'X seconds'"
     * MySQL: "DATE_SUB(now(6), INTERVAL X SECOND)"
     */
    String intervalSecondsBeforeNow(int seconds);

    /** 是否支持 UPDATE ... RETURNING 语法。 */
    boolean supportsReturning();

    /**
     * 判断 UPSERT 执行后是否是新增插入（而非更新既有行）。
     * PostgreSQL: 从 RETURNING (xmax = 0) AS inserted 获取
     * MySQL: 根据 JDBC executeUpdate 受影响行数（1=插入，2=更新，0=无变更更新）判定
     */
    boolean isUpsertInserted(int affectedRows, ResultSet rsIfReturning) throws SQLException;

    /**
     * 分页子句。
     */
    default String limitOffsetClause(long limit, long offset) {
        return "LIMIT " + limit + " OFFSET " + offset;
    }

    /**
     * 行锁子句。
     */
    default String forUpdateClause() {
        return "FOR UPDATE";
    }

    /**
     * 队列消费跳过已锁行子句。
     * PostgreSQL 与 MySQL 8.0: "FOR UPDATE SKIP LOCKED"
     * MySQL 5.7: "FOR UPDATE"
     */
    String forUpdateSkipLockedClause();

    /**
     * 大小写不敏感模糊匹配子句。
     * PostgreSQL: "col ILIKE ?"
     * MySQL 5.7 / 8.0: "LOWER(col) LIKE LOWER(?)"
     */
    String ilikeClause(String column);

    /**
     * 从结果集中读取 UTC OffsetDateTime。
     */
    default java.time.OffsetDateTime readOffsetDateTime(ResultSet rs, String columnLabel) throws SQLException {
        try {
            java.time.OffsetDateTime odt = rs.getObject(columnLabel, java.time.OffsetDateTime.class);
            if (odt != null) {
                return odt;
            }
        } catch (Exception ignored) {
        }
        java.sql.Timestamp ts = rs.getTimestamp(columnLabel);
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    /** 是否支持数据库原生数组类型（如 PostgreSQL ARRAY / ANY(?)）。 */
    boolean supportsArrayType();

    /**
     * 从结果集中按列索引读取 UTC OffsetDateTime。
     */
    default java.time.OffsetDateTime readOffsetDateTime(ResultSet rs, int columnIndex) throws SQLException {
        try {
            java.time.OffsetDateTime odt = rs.getObject(columnIndex, java.time.OffsetDateTime.class);
            if (odt != null) {
                return odt;
            }
        } catch (Exception ignored) {
        }
        java.sql.Timestamp ts = rs.getTimestamp(columnIndex);
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    /**
     * 生成插入并忽略唯一键冲突的语句：
     * PostgreSQL: "INSERT INTO ... VALUES (...) ON CONFLICT (<conflictTarget>) DO NOTHING"
     * MySQL: "INSERT IGNORE INTO ... VALUES (...)"
     */
    default String insertIgnoreSql(String qualifiedTable, String columns, String placeholders, String conflictTarget) {
        if (databaseType() == DatabaseType.MYSQL) {
            return "INSERT IGNORE INTO " + qualifiedTable + " (" + columns + ") VALUES (" + placeholders + ")";
        }
        return "INSERT INTO " + qualifiedTable + " (" + columns + ") VALUES (" + placeholders + ") ON CONFLICT (" + conflictTarget + ") DO NOTHING";
    }
}


