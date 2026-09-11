package com.lightai.starter.autoconfigure;

import com.lightai.admin.LightAiAdminAutoConfiguration;
import com.lightai.client.LightAiClient;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.storage.channel.JdbcChannelCredentialSecretPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BE-055: Servlet Embedded JDBC 自动装配")
class LightAiEmbeddedJdbcAutoConfigurationTest {

    @Test
    @DisplayName("管理存储 Bean 可组装默认凭证端口、运行管道与客户端")
    void embeddedRuntimeUsesAdminJdbcBeans() {
        new WebApplicationContextRunner()
                .withUserConfiguration(
                        LightAiAdminAutoConfiguration.class,
                        LightAiAutoConfiguration.class)
                .withBean(DataSource.class, LightAiEmbeddedJdbcAutoConfigurationTest::dataSource)
                .withPropertyValues(
                        "light-ai.mode=EMBEDDED",
                        "light-ai.application=jdbc-app",
                        "light-ai.storage.schema-mode=MIGRATE",
                        "light-ai.admin.secret-master-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "light-ai.admin.secret-master-key-id=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CredentialSecretPort.class);
                    assertThat(context.getBean(CredentialSecretPort.class))
                            .isInstanceOf(JdbcChannelCredentialSecretPort.class);
                    assertThat(context).hasSingleBean(ChatPipeline.class);
                    assertThat(context).hasSingleBean(LightAiClient.class);
                });
    }

    private static DataSource dataSource() {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:starter_embedded_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }
}
