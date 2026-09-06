package com.lightai.starter.datasource;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.dialect.DatabaseType;
import com.lightai.storage.dialect.DialectResolver;
import com.lightai.storage.provider.JdbcProviderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BE-056: 动态数据源（dynamic-datasource）多库路由与方言自适应测试")
class DynamicDataSourceRoutingTest {

    @AfterEach
    void tearDown() {
        DynamicDataSourceContextHolder.clear();
    }

    @Test
    @DisplayName("DynamicDataSourceContextHolder 栈式数据源切换与清理验证")
    void testContextHolderRouting() {
        assertThat(DynamicDataSourceContextHolder.peek()).isNull();

        DynamicDataSourceContextHolder.push("pg_master");
        assertThat(DynamicDataSourceContextHolder.peek()).isEqualTo("pg_master");

        DynamicDataSourceContextHolder.push("mysql_slave");
        assertThat(DynamicDataSourceContextHolder.peek()).isEqualTo("mysql_slave");

        DynamicDataSourceContextHolder.poll();
        assertThat(DynamicDataSourceContextHolder.peek()).isEqualTo("pg_master");

        DynamicDataSourceContextHolder.clear();
        assertThat(DynamicDataSourceContextHolder.peek()).isNull();
    }

    @Test
    @DisplayName("基于路由数据源动态切换 Connection 并自动解析对应方言（PostgreSQL <-> MySQL 5.7）")
    void testDynamicRoutingDialectResolution() throws SQLException {
        // 构建模拟的多数据源路由映射
        DataSource pgDataSource = createMockDataSource("jdbc:postgresql://127.0.0.1:5432/light_ai", "PostgreSQL");
        DataSource mysqlDataSource = createMockDataSource("jdbc:mysql://127.0.0.1:3306/light_ai?useSSL=false", "MySQL");

        Map<String, DataSource> targetDataSources = new HashMap<>();
        targetDataSources.put("pg_ds", pgDataSource);
        targetDataSources.put("mysql_ds", mysqlDataSource);

        // 创建基于 DynamicDataSourceContextHolder 的路由代理数据源
        DataSource routingDataSource = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        String currentKey = DynamicDataSourceContextHolder.peek();
                        DataSource target = targetDataSources.get(currentKey);
                        if (target == null) {
                            target = pgDataSource; // 默认数据源
                        }
                        return target.getConnection();
                    }
                    return null;
                });

        // 创建真实 JDBC 仓储实例（继承自 AbstractJdbcRepository）
        TestRoutingRepository repository = new TestRoutingRepository("light_ai");

        // 1. 默认或路由到 PostgreSQL
        DynamicDataSourceContextHolder.push("pg_ds");
        try (Connection conn = routingDataSource.getConnection()) {
            DatabaseDialect dialect = repository.inspectDialect(conn);
            assertThat(dialect.databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
            assertThat(dialect.qualify("light_ai", "provider")).isEqualTo("light_ai.provider");
            assertThat(dialect.nowFunction()).isEqualTo("now()");
            assertThat(dialect.supportsArrayType()).isTrue();
            assertThat(dialect.supportsReturning()).isTrue();
            assertThat(dialect.forUpdateSkipLockedClause()).isEqualTo("FOR UPDATE SKIP LOCKED");
        }

        // 2. 动态切换路由到 MySQL 5.7 / 8.0
        DynamicDataSourceContextHolder.push("mysql_ds");
        try (Connection conn = routingDataSource.getConnection()) {
            DatabaseDialect dialect = repository.inspectDialect(conn);
            assertThat(dialect.databaseType()).isEqualTo(DatabaseType.MYSQL);
            assertThat(dialect.qualify("light_ai", "provider")).isEqualTo("`provider`");
            assertThat(dialect.nowFunction()).isEqualTo("now(6)");
            assertThat(dialect.supportsArrayType()).isFalse();
            assertThat(dialect.supportsReturning()).isFalse();
            // MySQL 5.7 兼容：FOR UPDATE 无 SKIP LOCKED
            assertThat(dialect.forUpdateSkipLockedClause()).isEqualTo("FOR UPDATE");
            assertThat(dialect.insertIgnoreSql("`provider`", "id, code", "?, ?", "code"))
                    .isEqualTo("INSERT IGNORE INTO `provider` (id, code) VALUES (?, ?)");
        }

        // 3. 退出 MySQL 作用域，回到 PostgreSQL 栈
        DynamicDataSourceContextHolder.poll();
        try (Connection conn = routingDataSource.getConnection()) {
            DatabaseDialect dialect = repository.inspectDialect(conn);
            assertThat(dialect.databaseType()).isEqualTo(DatabaseType.POSTGRESQL);
        }
    }

    /** 辅助测试仓储：继承 AbstractJdbcRepository 暴露方言测试能力 */
    static class TestRoutingRepository extends AbstractJdbcRepository {
        TestRoutingRepository(String schemaName) {
            super(schemaName);
        }

        DatabaseDialect inspectDialect(Connection connection) {
            return dialect(connection);
        }
    }

    private DataSource createMockDataSource(String url, String productName) {
        Connection conn = createMockConnection(url, productName);
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return conn;
                    }
                    return null;
                });
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
