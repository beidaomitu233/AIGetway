package com.lightai.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.application.ApplicationMappingsBulkCreateCommand;
import com.lightai.client.application.ApplicationMappingsReplaceCommand;
import com.lightai.client.application.ApplicationModelMappingCommand;
import com.lightai.client.application.ApplicationModelTargetCommand;
import com.lightai.client.application.ApplicationModelTargetView;
import com.lightai.client.protocol.Roles;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.application.JdbcApplicationRepository;
import com.lightai.storage.audit.JdbcAuditRepository;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ApplicationMappingServiceTest {
    private ApplicationService service;
    private JdbcTemplate jdbc;
    private UUID applicationId;
    private UUID channelId;
    private UUID upstreamId;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:application_mapping_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        new DefaultSchemaMigrator(dataSource).migrate();
        jdbc = new JdbcTemplate(dataSource);
        var tx = new DataSourceTransactionManager(dataSource);
        tx.afterPropertiesSet();
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
        service = new ApplicationService(dataSource, new JdbcApplicationRepository(), new JdbcAliasRepository(),
                new AuditService(new JdbcAuditRepository(), dataSource, tx, (record, cause) -> { }), tx,
                new PageResultFactory(clock), clock, "STANDALONE_SERVER",
                () -> new ConfigSnapshotPort.ActiveSnapshot(1, List.of()));
        applicationId = UUID.fromString(service.create(admin(), new ApplicationCreateCommand(
                "mapping-app", "Mapping app", null, "owner", "Owner", "TEST", null, "ACTIVE",
                null, null, null, null, null, "LIFECYCLE", null, null, List.of())).id());
        channelId = UUID.randomUUID();
        upstreamId = UUID.randomUUID();
        jdbc.update("INSERT INTO channel (id, created_at, updated_at, provider_id, name, base_url) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)",
                channelId, UUID.fromString("11111111-1111-4111-8111-111111110001"), "test-channel", "https://example.test");
        jdbc.execute("DROP TABLE upstream_model");
    }

    @Test
    void bulkDraftAndReplacePersistCurrentVersion() {
        var draft = service.bulkCreateMappings(admin(), applicationId,
                new ApplicationMappingsBulkCreateCommand(List.of(channelId.toString()), "qwen", 10));
        assertThat(draft).isEmpty();
        var current = service.mappings(admin(), applicationId);
        var saved = service.replaceMappings(admin(), applicationId, new ApplicationMappingsReplaceCommand(
                current.applicationVersion(),
                List.of(new ApplicationModelMappingCommand(null, "assistant", "ACTIVE", List.of(
                        new ApplicationModelTargetCommand(channelId.toString(), upstreamId.toString(), "qwen-max",
                                10, 2, "ACTIVE", null)))),
                "configure assistant mapping"));
        assertThat(saved.entity().revision()).isEqualTo(1);
        assertThat(service.mappings(admin(), applicationId).mappings()).singleElement()
                .satisfies(mapping -> assertThat(mapping.publicModelName()).isEqualTo("assistant"));

        var replaced = service.replaceMappings(admin(), applicationId, new ApplicationMappingsReplaceCommand(
                saved.entity().applicationVersion(),
                List.of(new ApplicationModelMappingCommand(null, "assistant", "ACTIVE", List.of(
                        new ApplicationModelTargetCommand(channelId.toString(), upstreamId.toString(), "qwen-max",
                                10, 3, "ACTIVE", null)))),
                "adjust weight"));
        assertThat(service.mappings(admin(), applicationId).mappings()).singleElement()
                .satisfies(mapping -> assertThat(mapping.targets()).singleElement()
                        .extracting(ApplicationModelTargetView::weight).isEqualTo(3));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_model_target WHERE mapping_id = ? AND status = 'DISABLED'",
                Integer.class, UUID.fromString(replaced.entity().mappings().get(0).id()))).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT content_json FROM application_config_revision WHERE application_id = ? AND revision = 2",
                String.class, applicationId)).contains("assistant");
    }

    private static RequestContext admin() {
        return new RequestContext(AuthContext.authenticated("admin", "管理员",
                Set.of(Roles.SYSTEM_ADMIN), List.of(), List.of()), UUID.randomUUID().toString(), "127.0.0.1");
    }
}
