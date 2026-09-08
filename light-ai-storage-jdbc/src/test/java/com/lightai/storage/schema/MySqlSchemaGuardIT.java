package com.lightai.storage.schema;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真实 MySQL 5.7/8.0 迁移与结构门禁；连接由专用集成测试库环境变量提供。 */
@EnabledIfEnvironmentVariable(named = "LAI_IT_MYSQL_URL", matches = ".+")
class MySqlSchemaGuardIT {

    private static com.mysql.cj.jdbc.MysqlDataSource dataSource() {
        var dataSource = new com.mysql.cj.jdbc.MysqlDataSource();
        dataSource.setURL(System.getenv("LAI_IT_MYSQL_URL"));
        dataSource.setUser(System.getenv("LAI_IT_MYSQL_USER"));
        dataSource.setPassword(System.getenv("LAI_IT_MYSQL_PASSWORD"));
        return dataSource;
    }

    @Test
    void migrationIsRepeatableAndFullContractValidates() {
        DefaultSchemaMigrator migrator = new DefaultSchemaMigrator(dataSource());
        migrator.migrate();
        migrator.migrate();
        new SchemaGuard(dataSource()).validate();
    }

    @Test
    void connectionFailureIsNotReady() throws Exception {
        var broken = new com.mysql.cj.jdbc.MysqlDataSource();
        broken.setURL("jdbc:mysql://127.0.0.1:1/none?connectTimeout=100");
        broken.setUser("nobody");
        broken.setPassword("nothing");
        assertThatThrownBy(() -> new SchemaGuard(broken).validate())
                .isInstanceOf(SchemaNotReadyException.class);
    }
}
