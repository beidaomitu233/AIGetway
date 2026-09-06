package com.lightai.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultSchemaMigratorTest {

    @Test
    void splitStatementsHandlesCommentsAndSemicolons() {
        String sql = """
                -- Comment line 1
                CREATE TABLE foo (
                    id VARCHAR(36) PRIMARY KEY,
                    val VARCHAR(100) DEFAULT 'hello;world'
                );
                -- Another comment
                INSERT INTO foo (id, val) VALUES ('1', 'it''s a test; yes');
                """;
        List<String> stmts = DefaultSchemaMigrator.splitStatements(sql);
        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0)).contains("CREATE TABLE foo");
        assertThat(stmts.get(0)).contains("'hello;world'");
        assertThat(stmts.get(1)).contains("INSERT INTO foo");
        assertThat(stmts.get(1)).contains("'it''s a test; yes'");
    }

    @Test
    void loadScriptLoadsPostgresAndMysqlScripts() {
        String pg = DefaultSchemaMigrator.loadScript("schema/postgres/light_ai_schema.sql");
        assertThat(pg).contains("CREATE SCHEMA IF NOT EXISTS light_ai");
        assertThat(pg).contains("CREATE TABLE IF NOT EXISTS light_ai.provider");
        List<String> pgStmts = DefaultSchemaMigrator.splitStatements(pg);
        assertThat(pgStmts.size()).isGreaterThanOrEqualTo(39);

        String mysql = DefaultSchemaMigrator.loadScript("schema/mysql/light_ai_schema.sql");
        assertThat(mysql).contains("CREATE TABLE IF NOT EXISTS provider");
        List<String> mysqlStmts = DefaultSchemaMigrator.splitStatements(mysql);
        assertThat(mysqlStmts.size()).isGreaterThanOrEqualTo(39);
    }
}
