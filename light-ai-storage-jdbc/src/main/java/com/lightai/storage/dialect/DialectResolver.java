package com.lightai.storage.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 方言自动探测与解析器：基于 Connection 元数据自动识别数据库类型并缓存。
 */
public final class DialectResolver {

    private static final ConcurrentMap<String, DatabaseDialect> CACHE = new ConcurrentHashMap<>();

    private DialectResolver() {
    }

    /**
     * 根据活动数据库连接解析匹配的方言。
     */
    public static DatabaseDialect resolve(Connection connection) {
        if (connection == null) {
            return PostgresDialect.INSTANCE;
        }
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            if (metaData == null) {
                return PostgresDialect.INSTANCE;
            }
            String url = metaData.getURL();
            if (url != null && !url.isBlank()) {
                return CACHE.computeIfAbsent(url, u -> detect(metaData));
            }
            return detect(metaData);
        } catch (SQLException e) {
            return PostgresDialect.INSTANCE;
        }
    }

    /**
     * 根据数据库产品名解析方言。
     */
    public static DatabaseDialect resolveByProductName(String productName) {
        if (productName == null) {
            return PostgresDialect.INSTANCE;
        }
        String lower = productName.toLowerCase();
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return MySqlDialect.INSTANCE;
        }
        return PostgresDialect.INSTANCE;
    }

    private static DatabaseDialect detect(DatabaseMetaData metaData) {
        try {
            String productName = metaData.getDatabaseProductName();
            return resolveByProductName(productName);
        } catch (SQLException e) {
            return PostgresDialect.INSTANCE;
        }
    }

    /** 清理方言缓存（测试用途）。 */
    public static void clearCache() {
        CACHE.clear();
    }
}
