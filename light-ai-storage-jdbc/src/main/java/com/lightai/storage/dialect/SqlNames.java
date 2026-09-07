package com.lightai.storage.dialect;

/**
 * 管理面手写 SQL 的表名修饰（与 MySqlDialect.qualify 口径一致）：
 * MySQL 的 schema 即 catalog，默认 light_ai 时必须使用当前数据库名；
 * 显式配置了其他 schema 时按 schema.table 拼接（PostgreSQL 语义）。
 */
public final class SqlNames {

    public static final String DEFAULT_SCHEMA = "light_ai";

    private SqlNames() {
    }

    /** 返回可直接拼进 SQL 的表名（默认 schema 时为未限定表名）。 */
    public static String table(String schemaName, String tableName) {
        if (schemaName == null || schemaName.isBlank() || DEFAULT_SCHEMA.equalsIgnoreCase(schemaName)) {
            return tableName;
        }
        return "`" + schemaName + "`.`" + tableName + "`";
    }
}
