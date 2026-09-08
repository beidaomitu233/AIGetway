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
                        LightAiAdminAutoConfigurationTest::fullSchemaDataSource)
                .withPropertyValues(
                        // 32 字节全零 Base64，仅用于装配测试
                        "light-ai.admin.secret-master-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "light-ai.admin.secret-master-key-id=test-key",
                        "light-ai.storage.schema-mode=MIGRATE");
        withDataSource.run(context -> {
            assertThat(context).hasBean("lightAiDraftStateRepository");
            assertThat(context).hasBean("lightAiAuditRepository");
            assertThat(context).hasBean("lightAiDraftWriteService");
            assertThat(context).hasBean("lightAiManagementStateReader");
            assertThat(context).hasBean("lightAiCredentialService");
            assertThat(context).hasBean("lightAiProviderModelService");
            assertThat(context).hasBean("lightAiModelAliasController");
            assertThat(context).hasBean("lightAiSecretCipher");
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

    /** 独立 H2 空库，由真实迁移初始化后通过 SchemaGuard 完整检查。 */
    private static DataSource fullSchemaDataSource() {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:admin_autoconfig_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }
}
