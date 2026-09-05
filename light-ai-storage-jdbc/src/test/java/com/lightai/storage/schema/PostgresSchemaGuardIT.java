package com.lightai.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真实 PostgreSQL 集成测试：仅当提供 LAI_IT_DB_URL/LAI_IT_DB_USER/LAI_IT_DB_PASSWORD 时执行，
 * 本地无数据库环境时跳过并如实报告（不得以跳过冒充通过）。
 * 用途：验证 information_schema 读取路径与真实方言行为；迁移表结构由 DB-P01 提供。
 */
@EnabledIfEnvironmentVariable(named = "LAI_IT_DB_URL", matches = ".+")
class PostgresSchemaGuardIT {

    private static org.postgresql.ds.PGSimpleDataSource dataSource() {
        var ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setURL(System.getenv("LAI_IT_DB_URL"));
        ds.setUser(System.getenv("LAI_IT_DB_USER"));
        ds.setPassword(System.getenv("LAI_IT_DB_PASSWORD"));
        return ds;
    }

    @Test
    void emptySchemaReportsAllMissingTables() throws Exception {
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS light_ai");
        }
        SchemaGuard guard = new SchemaGuard(dataSource());
        assertThatThrownBy(guard::validate)
                .isInstanceOf(SchemaNotReadyException.class)
                .extracting("missingTables")
                .isEqualTo(ExpectedSchema.missingTables(Set.of()));
    }

    @Test
    void guardAcceptsRealConnectionFailureAsNotReady() {
        var broken = new org.postgresql.ds.PGSimpleDataSource();
        broken.setURL("jdbc:postgresql://127.0.0.1:1/none");
        broken.setUser("nobody");
        broken.setPassword("nothing");
        SchemaGuard guard = new SchemaGuard(broken);
        assertThatThrownBy(guard::validate).isInstanceOf(SchemaNotReadyException.class);
    }

    @Test
    void missingTablesDiffIsDeterministic() {
        List<String> missing = ExpectedSchema.missingTables(Set.of("provider", "audit_log"));
        assertThat(missing).hasSize(37).contains("draft_change", "trace").doesNotContain("provider");
    }
}
