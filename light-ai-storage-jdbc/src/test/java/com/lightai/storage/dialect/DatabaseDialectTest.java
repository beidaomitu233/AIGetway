package com.lightai.storage.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDialectTest {

    @Test
    @DisplayName("PostgreSQL 方言特性与语法生成验证")
    void testPostgresDialect() throws SQLException {
        DatabaseDialect dialect = PostgresDialect.INSTANCE;

        assertThat(dialect.databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(dialect.qualify("light_ai", "provider")).isEqualTo("light_ai.provider");
        assertThat(dialect.qualify("", "provider")).isEqualTo("provider");
        assertThat(dialect.qualify(null, "provider")).isEqualTo("provider");
        assertThat(dialect.jsonPlaceholder()).isEqualTo("?::jsonb");
        assertThat(dialect.nowFunction()).isEqualTo("now()");
        assertThat(dialect.intervalSecondsBeforeNow(120)).isEqualTo("now() - interval '120 seconds'");
        assertThat(dialect.supportsReturning()).isTrue();
        assertThat(dialect.forUpdateSkipLockedClause()).isEqualTo("FOR UPDATE SKIP LOCKED");
        assertThat(dialect.ilikeClause("name")).isEqualTo("name ILIKE ?");
        assertThat(dialect.limitOffsetClause(10, 20)).isEqualTo("LIMIT 10 OFFSET 20");
    }

    @Test
    @DisplayName("MySQL 5.7 / 8.0 方言特性与语法生成验证")
    void testMySqlDialect() throws SQLException {

        DatabaseDialect dialect = MySqlDialect.INSTANCE;

        assertThat(dialect.databaseType()).isEqualTo(DatabaseType.MYSQL);
        assertThat(dialect.qualify("light_ai", "provider")).isEqualTo("`provider`");
        assertThat(dialect.qualify("custom_db", "provider")).isEqualTo("`custom_db`.`provider`");
        assertThat(dialect.qualify("", "provider")).isEqualTo("`provider`");
        assertThat(dialect.qualify(null, "provider")).isEqualTo("`provider`");
        assertThat(dialect.jsonPlaceholder()).isEqualTo("?");
        assertThat(dialect.nowFunction()).isEqualTo("now(6)");
        assertThat(dialect.intervalSecondsBeforeNow(120)).isEqualTo("DATE_SUB(now(6), INTERVAL 120 SECOND)");
        assertThat(dialect.supportsReturning()).isFalse();
        assertThat(dialect.isUpsertInserted(1, null)).isTrue();
        assertThat(dialect.isUpsertInserted(2, null)).isFalse();
        assertThat(dialect.isUpsertInserted(0, null)).isFalse();
        assertThat(dialect.forUpdateSkipLockedClause()).isEqualTo("FOR UPDATE"); // 兼容 5.7
        assertThat(dialect.ilikeClause("name")).isEqualTo("LOWER(name) LIKE LOWER(?)");
        assertThat(dialect.limitOffsetClause(10, 20)).isEqualTo("LIMIT 10 OFFSET 20");
    }


    @Test
    @DisplayName("DialectResolver 数据库自动识别与解析")
    void testDialectResolver() throws SQLException {
        assertThat(DialectResolver.resolveByProductName("PostgreSQL")).isSameAs(PostgresDialect.INSTANCE);
        assertThat(DialectResolver.resolveByProductName("MySQL")).isSameAs(MySqlDialect.INSTANCE);
        assertThat(DialectResolver.resolveByProductName("MariaDB")).isSameAs(MySqlDialect.INSTANCE);
        assertThat(DialectResolver.resolveByProductName("Oracle")).isSameAs(PostgresDialect.INSTANCE);
        assertThat(DialectResolver.resolve(null)).isSameAs(PostgresDialect.INSTANCE);

        // 动态代理模拟 Connection 元数据探测
        Connection pgConn = createMockConnection("jdbc:postgresql://localhost:5432/light_ai", "PostgreSQL");
        assertThat(DialectResolver.resolve(pgConn)).isSameAs(PostgresDialect.INSTANCE);

        Connection myConn = createMockConnection("jdbc:mysql://localhost:3306/light_ai", "MySQL");
        assertThat(DialectResolver.resolve(myConn)).isSameAs(MySqlDialect.INSTANCE);
    }

    private Connection createMockConnection(String url, String productName) {
        DatabaseMetaData meta = (DatabaseMetaData) Proxy.newProxyInstance(
                DatabaseMetaData.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getURL".equals(method.getName())) {
                        return url;
                    }
                    if ("getDatabaseProductName".equals(method.getName())) {
                        return productName;
                    }
                    return null;
                });

        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return meta;
                    }
                    return null;
                });
    }
}
