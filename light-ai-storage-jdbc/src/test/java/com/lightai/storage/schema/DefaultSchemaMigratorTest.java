package com.lightai.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
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
        String pg = DefaultSchemaMigrator.loadScript("db/migration/postgres/V1__baseline.sql");
        assertThat(pg).contains("CREATE SCHEMA IF NOT EXISTS light_ai");
        assertThat(pg).contains("CREATE TABLE IF NOT EXISTS light_ai.provider");
        List<String> pgStmts = DefaultSchemaMigrator.splitStatements(pg);
        assertThat(pgStmts.size()).isGreaterThanOrEqualTo(39);

        String mysql = DefaultSchemaMigrator.loadScript("db/migration/mysql/V1__baseline.sql");
        assertThat(mysql).contains("CREATE TABLE IF NOT EXISTS provider");
        List<String> mysqlStmts = DefaultSchemaMigrator.splitStatements(mysql);
        assertThat(mysqlStmts.size()).isGreaterThanOrEqualTo(39);
    }

    @Test
    void migrateCreatesVersionHistoryAndIsRepeatableOnEmptyDatabase() throws Exception {
        JdbcDataSource dataSource = h2DataSource();
        DefaultSchemaMigrator migrator = new DefaultSchemaMigrator(dataSource);

        migrator.migrate();
        migrator.migrate();

        new SchemaGuard(dataSource).validate();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT version, description, checksum, success FROM light_ai_schema_history ORDER BY version")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("version")).isEqualTo(1);
            assertThat(resultSet.getString("description")).isEqualTo("baseline");
            assertThat(resultSet.getString("checksum")).hasSize(64);
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("version")).isEqualTo(2);
            assertThat(resultSet.getString("description")).isEqualTo("enterprise_application_foundation");
            assertThat(resultSet.getString("checksum")).hasSize(64);
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("version")).isEqualTo(3);
            assertThat(resultSet.getString("description")).isEqualTo("application_key_model_scope");
            assertThat(resultSet.getString("checksum")).hasSize(64);
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("version")).isEqualTo(4);
            assertThat(resultSet.getString("description"))
                    .isEqualTo("resource_domain_channels_and_upstream_models");
            assertThat(resultSet.getString("checksum")).hasSize(64);
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("version")).isEqualTo(5);
            assertThat(resultSet.getString("description"))
                    .isEqualTo("virtual_model_routes_and_sync");
            assertThat(resultSet.getString("checksum")).hasSize(64);
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("version")).isEqualTo(7);
            assertThat(resultSet.getString("description"))
                    .isEqualTo("admission_ledger_observation_retention");
            assertThat(resultSet.getString("checksum")).hasSize(64);
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt("version")).isEqualTo(DefaultSchemaMigrator.LATEST_VERSION);
            assertThat(resultSet.getString("description"))
                    .isEqualTo("audit_and_instance_gate_indexes");
            assertThat(resultSet.getString("checksum")).hasSize(64);
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void migrateRejectsChangedPublishedMigrationChecksum() throws Exception {
        JdbcDataSource dataSource = h2DataSource();
        DefaultSchemaMigrator migrator = new DefaultSchemaMigrator(dataSource);
        migrator.migrate();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE light_ai_schema_history SET checksum = 'tampered' WHERE version = 1");
        }

        assertThatThrownBy(migrator::migrate)
                .isInstanceOf(SchemaNotReadyException.class)
                .hasMessageContaining("校验值不一致");
    }

    private static JdbcDataSource h2DataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:migration_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }
}
