package com.lightai.admin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.admin.application.ApplicationService;
import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.application.ApplicationMappingsReplaceCommand;
import com.lightai.client.application.ApplicationModelMappingCommand;
import com.lightai.client.application.ApplicationModelTargetCommand;
import com.lightai.client.protocol.Roles;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.application.JdbcApplicationModelMappingRepository;
import com.lightai.storage.application.JdbcApplicationRepository;
import com.lightai.storage.audit.JdbcAuditRepository;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ApplicationRuntimeSnapshotTest {

    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private ApplicationService applications;
    private UUID applicationId;
    private UUID channelId;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:application_runtime_snapshot_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        new DefaultSchemaMigrator(dataSource).migrate();
        jdbc = new JdbcTemplate(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        transactions.afterPropertiesSet();
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
        applications = new ApplicationService(dataSource, new JdbcApplicationRepository(), new JdbcAliasRepository(),
                new AuditService(new JdbcAuditRepository(), dataSource, transactions, (record, cause) -> { }),
                transactions, new PageResultFactory(clock), clock, "STANDALONE_SERVER", ConfigSnapshotPort.empty());
        applicationId = UUID.fromString(applications.create(admin(), new ApplicationCreateCommand(
                "runtime-app", "Runtime app", null, "owner", "Owner", "TEST", null, "ACTIVE",
                null, null, null, null, null, "LIFECYCLE", null, null, List.of())).id());
        channelId = UUID.randomUUID();
        jdbc.update("INSERT INTO channel (id, created_at, updated_at, provider_id, name, base_url) "
                        + "VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)",
                channelId, UUID.fromString("11111111-1111-4111-8111-111111110001"),
                "runtime-channel", "http://127.0.0.1:19090");
    }

    @Test
    void resolvesManualApplicationMappingForModelsAndRuntimeRouting() throws Exception {
        var saved = applications.replaceMappings(admin(), applicationId, new ApplicationMappingsReplaceCommand(
                1, List.of(new ApplicationModelMappingCommand(null, "assistant", "ACTIVE", List.of(
                        new ApplicationModelTargetCommand(channelId.toString(), null, "qwen-max",
                                1, 100, "ACTIVE", null)))), "runtime mapping"));

        AccessTokenPort.Principal principal = AccessTokenPort.Principal.enterprise(
                "runtime-app", List.of("assistant"), applicationId.toString(), UUID.randomUUID().toString(),
                null, null, Map.of(), Map.of("assistant", "assistant"));
        JdbcConfigSnapshotPortAdapter adapter = new JdbcConfigSnapshotPortAdapter("light_ai", dataSource);

        ConfigSnapshotPort.ActiveSnapshot snapshot = adapter.active(principal);
        assertThat(snapshot.aliases()).singleElement().satisfies(alias -> {
            assertThat(alias.alias()).isEqualTo("assistant");
            assertThat(alias.enabledCandidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.modelId()).isEqualTo("qwen-max");
                assertThat(candidate.providerType()).isEqualTo("OPENAI");
                assertThat(candidate.channelId()).isEqualTo(channelId.toString());
                assertThat(candidate.baseUrl()).isEqualTo("http://127.0.0.1:19090");
            });
        });
        try (var connection = dataSource.getConnection()) {
            assertThat(new JdbcApplicationModelMappingRepository().runtimeMappings(connection, applicationId))
                    .containsEntry("assistant", "assistant");
        }
        assertThat(saved.entity().revision()).isEqualTo(1);
    }

    private static RequestContext admin() {
        return new RequestContext(AuthContext.authenticated("admin", "管理员",
                Set.of(Roles.SYSTEM_ADMIN), List.of(), List.of()), UUID.randomUUID().toString(), "127.0.0.1");
    }
}