package com.lightai.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.admin.bootstrap.BootstrapController;
import com.lightai.admin.storage.JdbcManagementStateReader;
import com.lightai.storage.draft.JdbcDraftStateRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * 自动装配条件验证：Web 环境下默认装配管理端；
 * 无 DataSource 时不装配 JDBC 仓储，不假装连接数据库。
 */
class LightAiAdminAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(LightAiAdminAutoConfiguration.class);

    @Test
    void adminWebBeansAreRegisteredWithoutStorage() {
        runner.run(context -> {
            assertThat(context).hasBean("lightAiBootstrapController");
            assertThat(context).hasBean("lightAiBootstrapService");
            assertThat(context).hasBean("lightAiAdminAuthInterceptor");
            assertThat(context).hasSingleBean(com.lightai.spi.auth.AuthContextProvider.class);
            assertThat(context).getBean(com.lightai.spi.auth.AuthContextProvider.class)
                    .matches(provider -> !provider.resolve(null).authenticated());
            assertThat(context).doesNotHaveBean(JdbcManagementStateReader.class);
            assertThat(context).doesNotHaveBean(JdbcDraftStateRepository.class);
        });
    }

    @Test
    void disabledByProperty() {
        runner.withPropertyValues("light-ai.admin.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BootstrapController.class));
    }

    @Test
    void illegalRuntimeModeFailsStartup() {
        runner.withPropertyValues("light-ai.admin.runtime-mode=NOT_A_MODE")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void csrfFilterOnlyRegisteredWhenEnabled() {
        runner.run(context ->
                assertThat(context).doesNotHaveBean("lightAiCsrfTokenFilter"));
        runner.withPropertyValues("light-ai.admin.csrf-enabled=true")
                .run(context -> assertThat(context).hasBean("lightAiCsrfTokenFilter"));
    }

    @Test
    void storageBeansWiredWhenDataSourceReportsFullSchema() {
        WebApplicationContextRunner withDataSource = runner.withBean(DataSource.class,
                LightAiAdminAutoConfigurationTest::fullSchemaDataSource);
        withDataSource.run(context -> {
            assertThat(context).hasBean("lightAiDraftStateRepository");
            assertThat(context).hasBean("lightAiAuditRepository");
            assertThat(context).hasBean("lightAiDraftWriteService");
            assertThat(context).hasBean("lightAiManagementStateReader");
        });
    }

    @Test
    void storageBeansAbsentWhenStorageDisabled() {
        runner.withBean(DataSource.class,
                        LightAiAdminAutoConfigurationTest::fullSchemaDataSource)
                .withPropertyValues("light-ai.storage.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JdbcDraftStateRepository.class);
                    assertThat(context).doesNotHaveBean(JdbcManagementStateReader.class);
                });
    }

    /** 伪造 report 全部产品表的 DataSource，使 SchemaGuard 启动检查通过。 */
    private static DataSource fullSchemaDataSource() {
        java.util.Iterator<String> iterator =
                com.lightai.storage.schema.ExpectedSchema.TABLES.iterator();
        class Rows {
            String current;
            boolean next() {
                if (iterator.hasNext()) {
                    current = iterator.next();
                    return true;
                }
                return false;
            }
        }
        Rows rows = new Rows();
        java.sql.ResultSet resultSet = (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                LightAiAdminAutoConfigurationTest.class.getClassLoader(),
                new Class<?>[] {java.sql.ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> rows.next();
                    case "getString" -> rows.current;
                    default -> null;
                });
        java.sql.DatabaseMetaData metaData =
                (java.sql.DatabaseMetaData) java.lang.reflect.Proxy.newProxyInstance(
                        LightAiAdminAutoConfigurationTest.class.getClassLoader(),
                        new Class<?>[] {java.sql.DatabaseMetaData.class},
                        (proxy, method, args) -> "getTables".equals(method.getName()) ? resultSet : null);
        java.sql.Connection connection = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                LightAiAdminAutoConfigurationTest.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (proxy, method, args) -> "getMetaData".equals(method.getName()) ? metaData : null);
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                LightAiAdminAutoConfigurationTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> "getConnection".equals(method.getName()) ? connection : null);
    }
}
