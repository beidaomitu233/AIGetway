package com.lightai.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * DB-233 迁移兼容（V1→V5 前向升级）：模拟已运行 V1 的存量库（含业务数据与边界数据），
 * 逐版本升级到当前版本后对账。V4 资源域数据映射（provider→channel、
 * credential→channel_credential、provider_model→upstream_model、route_candidate 列语义）
 * 与 V5 虚拟模型域更名（model_alias→virtual_model、alias→code、alias_id→virtual_model_id）
 * 必须保持逐表数量一致且业务字段可追溯。
 * 脏 URL、缺价格等边界按实际语义随行迁移（迁移不校验内容）；重复名称属阻断升级的
 * 唯一冲突，由失败重跑测试覆盖（DB-233：不可迁移项边界与失败重跑）。
 */
class UpgradeCompatibilityTest {

    private static final String HISTORY_DDL = """
            CREATE TABLE light_ai_schema_history (
                version INT PRIMARY KEY,
                description VARCHAR(200) NOT NULL,
                checksum CHAR(64) NOT NULL,
                installed_on TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                success BOOLEAN NOT NULL
            )
            """;

    @Test
    void upgradesV1BusinessDataToCurrentSchemaWithRowReconciliation() throws Exception {
        JdbcDataSource dataSource = h2("upgrade_compat_ok_");
        UUID providerA = UUID.randomUUID();
        UUID providerB = UUID.randomUUID();
        UUID poolA = UUID.randomUUID();
        UUID modelLegacy = UUID.randomUUID();
        UUID modelNoPrice = UUID.randomUUID();
        UUID aliasChat = UUID.randomUUID();
        seedV1Deployment(dataSource, providerA, providerB, poolA, modelLegacy, modelNoPrice,
                aliasChat, "legacy-provider", "legacy-provider-2");
        markV1Applied(dataSource);
        new DefaultSchemaMigrator(dataSource).migrate();
        new SchemaGuard(dataSource).validate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 逐表数量对账：两张 provider 均映射为 channel；仅 providerA 携带 Key
            assertThat(count(statement, "channel")).isEqualTo(2);
            assertThat(count(statement, "channel_credential")).isEqualTo(1);
            assertThat(count(statement, "upstream_model")).isEqualTo(2);
            assertThat(count(statement, "virtual_model")).isEqualTo(1);
            assertThat(count(statement, "route_candidate")).isEqualTo(1);
            assertThat(count(statement, "channel_check_record")).isEqualTo(1);
            // 业务字段可追溯：渠道 base_url/名称来自 provider，脏 URL 随行迁移不校验
            try (ResultSet rs = statement.executeQuery(
                    "SELECT base_url, health FROM channel WHERE id = '" + providerA + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("https://legacy.invalid/api/");
                assertThat(rs.getString(2)).isEqualTo("UNKNOWN");
            }
            // 候选列语义：credential_pool_id 已 remap 为渠道 id（= 原 provider id），
            // alias_id 已更名为 virtual_model_id 且仍指向原虚拟模型行
            try (ResultSet rs = statement.executeQuery(
                    "SELECT channel_id, virtual_model_id FROM route_candidate "
                            + "WHERE weight = 7")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(providerA.toString());
                assertThat(rs.getString(2)).isEqualTo(aliasChat.toString());
            }
        }
    }

    @Test
    void duplicateProviderNameBlocksUpgradeAndRecoversAfterDataFix() throws Exception {
        JdbcDataSource dataSource = h2("upgrade_compat_dup_");
        UUID providerA = UUID.randomUUID();
        UUID providerB = UUID.randomUUID();
        UUID poolA = UUID.randomUUID();
        UUID modelLegacy = UUID.randomUUID();
        UUID modelNoPrice = UUID.randomUUID();
        UUID aliasChat = UUID.randomUUID();
        seedV1Deployment(dataSource, providerA, providerB, poolA, modelLegacy, modelNoPrice,
                aliasChat, "same-name", "same-name");
        markV1Applied(dataSource);
        DefaultSchemaMigrator migrator = new DefaultSchemaMigrator(dataSource);

        // uk_channel_name 唯一冲突阻断升级（DB-233：不可迁移项边界）
        assertThatThrownBy(migrator::migrate).isInstanceOf(Exception.class);

        // 失败重跑：修复数据后从已应用版本继续，已发布迁移不重复执行
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE provider SET name = 'same-name-fixed' "
                    + "WHERE id = '" + providerA + "'");
        }
        migrator.migrate();
        new SchemaGuard(dataSource).validate();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThat(count(statement, "channel")).isEqualTo(2);
            assertThat(count(statement,
                    "SELECT COUNT(DISTINCT name) FROM channel")).isEqualTo(2);
        }
    }

    /** 建库并执行 V1 基线脚本，写入存量业务数据；此时 history 尚未登记。 */
    private void seedV1Deployment(JdbcDataSource dataSource, UUID providerA, UUID providerB,
                                  UUID poolA, UUID modelLegacy, UUID modelNoPrice, UUID aliasChat,
                                  String providerNameA, String providerNameB) throws Exception {
        runScript(dataSource, DefaultSchemaMigrator.loadScript(
                "db/migration/mysql/V1__baseline.sql"));

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO provider (id, created_at, updated_at, version, "
                    + "name, type, base_url, connect_timeout_ms, read_timeout_ms, enabled) VALUES "
                    + "('" + providerA + "', TIMESTAMP '2020-01-01 00:00:00', "
                    + "TIMESTAMP '2020-01-01 00:00:00', 1, '" + providerNameA + "', 'OPENAI', "
                    + "'https://legacy.invalid/api/', 3000, 120000, TRUE)");
            statement.executeUpdate("INSERT INTO provider (id, created_at, updated_at, version, "
                    + "name, type, base_url, connect_timeout_ms, read_timeout_ms, enabled) VALUES "
                    + "('" + providerB + "', TIMESTAMP '2020-01-02 00:00:00', "
                    + "TIMESTAMP '2020-01-02 00:00:00', 1, '" + providerNameB + "', 'OPENAI', "
                    + "'https://legacy-2.invalid/api/', 3000, 120000, TRUE)");
            // 有密钥 provider：经 credential_pool/credential/credential_secret 携带密文
            statement.executeUpdate("INSERT INTO credential_pool (id, created_at, updated_at, "
                    + "version, provider_id, name) VALUES "
                    + "('" + poolA + "', TIMESTAMP '2020-01-01 00:00:00', "
                    + "TIMESTAMP '2020-01-01 00:00:00', 1, '" + providerA + "', 'pool')");
            statement.executeUpdate("INSERT INTO credential (id, created_at, updated_at, version, "
                    + "pool_id, name, weight, enabled) VALUES "
                    + "('" + UUID.randomUUID() + "', TIMESTAMP '2020-01-01 00:00:00', "
                    + "TIMESTAMP '2020-01-01 00:00:00', 1, '" + poolA + "', 'key-1', 10, TRUE)");
            statement.executeUpdate("INSERT INTO credential_secret (id, created_at, updated_at, "
                    + "credential_id, secret_ciphertext, encryption_key_id, masked_value, "
                    + "secret_version) VALUES "
                    + "('" + UUID.randomUUID() + "', TIMESTAMP '2020-01-01 00:00:00', "
                    + "TIMESTAMP '2020-01-01 00:00:00', (SELECT id FROM credential LIMIT 1), "
                    + "X'00AA', 'legacy-kid', 'lai-masked', 1)");
            statement.executeUpdate("INSERT INTO provider_model (id, created_at, updated_at, version, "
                    + "provider_id, model_id, display_name, enabled) VALUES "
                    + "('" + modelLegacy + "', TIMESTAMP '2020-01-01 00:00:00', "
                    + "TIMESTAMP '2020-01-01 00:00:00', 1, '" + providerA + "', 'legacy-model', "
                    + "'Legacy Model', TRUE)");
            // 无密钥、缺价格边界：providerB 的模型无价格列值（V1 默认为 0）且无 Key
            statement.executeUpdate("INSERT INTO provider_model (id, created_at, updated_at, version, "
                    + "provider_id, model_id, display_name, enabled) VALUES "
                    + "('" + modelNoPrice + "', TIMESTAMP '2020-01-01 00:00:00', "
                    + "TIMESTAMP '2020-01-01 00:00:00', 1, '" + providerB + "', "
                    + "'no-price-model', 'No Price', TRUE)");
            statement.executeUpdate("INSERT INTO model_alias (id, created_at, updated_at, version, "
                    + "alias, display_name, enabled) VALUES "
                    + "('" + aliasChat + "', TIMESTAMP '2020-01-01 00:00:00', "
                    + "TIMESTAMP '2020-01-01 00:00:00', 1, 'legacy-chat', 'Legacy Chat', TRUE)");
            statement.executeUpdate("INSERT INTO route_candidate (id, created_at, updated_at, version, "
                    + "alias_id, provider_model_id, credential_pool_id, weight, enabled) VALUES "
                    + "('" + UUID.randomUUID() + "', TIMESTAMP '2020-01-01 00:00:00', "
                    + "TIMESTAMP '2020-01-01 00:00:00', 1, '" + aliasChat + "', '" + modelLegacy
                    + "', '" + poolA + "', 7, TRUE)");
            statement.executeUpdate("INSERT INTO provider_check_record (id, created_at, target_type, "
                    + "target_id, mode, status, operator_id, started_at, ended_at, total_ms) VALUES "
                    + "('" + UUID.randomUUID() + "', TIMESTAMP '2020-01-01 00:00:00', 'CHANNEL', "
                    + "'" + providerA + "', 'CONNECTION_ONLY', 'SUCCEEDED', 'legacy', "
                    + "TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2020-01-01 00:00:01', 1000)");
        }
    }

    /** 模拟存量库已完成 V1：登记带正确校验值的基线历史。 */
    private void markV1Applied(JdbcDataSource dataSource) throws Exception {
        String checksum = DefaultSchemaMigrator.checksum(DefaultSchemaMigrator.loadScript(
                "db/migration/mysql/V1__baseline.sql"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(HISTORY_DDL);
        }
        String insert = "INSERT INTO light_ai_schema_history "
                + "(version, description, checksum, success) VALUES (1, 'baseline', ?, TRUE)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, checksum);
            statement.executeUpdate();
        }
    }

    private void runScript(JdbcDataSource dataSource, String script) throws Exception {
        for (String sql : DefaultSchemaMigrator.splitStatements(
                DefaultSchemaMigrator.adaptMySqlScriptForH2(script))) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private long count(Statement statement, String tableOrQuery) throws SQLException {
        String sql = tableOrQuery.startsWith("SELECT")
                ? tableOrQuery
                : "SELECT COUNT(*) FROM " + tableOrQuery;
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private JdbcDataSource h2(String prefix) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + prefix + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }
}
