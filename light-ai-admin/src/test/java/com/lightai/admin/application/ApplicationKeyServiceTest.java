package com.lightai.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.accesscred.AccessTokenAuthService;
import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.security.AccessTokenService;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.application.ApplicationKeyCreateCommand;
import com.lightai.client.application.ApplicationKeyRevokeCommand;
import com.lightai.client.application.ApplicationKeyRotateCommand;
import com.lightai.client.application.ApplicationKeyStatusCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Roles;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.access.JdbcAccessCredentialRepository;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.application.JdbcApplicationKeyRepository;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ApplicationKeyServiceTest {

    private JdbcDataSource dataSource;
    private JdbcApplicationRepository applications;
    private JdbcApplicationKeyRepository keys;
    private AccessTokenService tokenService;
    private ApplicationKeyService service;
    private UUID applicationId;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:application_key_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        new DefaultSchemaMigrator(dataSource).migrate();
        var transactions = new DataSourceTransactionManager(dataSource);
        transactions.afterPropertiesSet();
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T09:00:00Z"), ZoneOffset.UTC);
        AuditService audits = new AuditService(new JdbcAuditRepository(), dataSource,
                transactions, (record, cause) -> { });
        applications = new JdbcApplicationRepository();
        keys = new JdbcApplicationKeyRepository();
        tokenService = new AccessTokenService(AccessTokenService.fixedPepper(1, "test-pepper"));
        ApplicationService applicationService = new ApplicationService(
                dataSource, applications, new JdbcAliasRepository(), audits, transactions,
                new PageResultFactory(clock), clock, "STANDALONE_SERVER");
        applicationId = UUID.fromString(applicationService.create(admin(), new ApplicationCreateCommand(
                "service-desk", "服务台助手", "IT", "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", 100, 50_000L,
                "MONTH", null, null, List.of())).id());
        service = new ApplicationKeyService(dataSource, applications, keys, tokenService,
                audits, transactions, clock, "STANDALONE_SERVER");
    }

    @Test
    void issuesOnceAuthenticatesDisablesEnablesRotatesAndRevokesApplicationKey() {
        var issued = service.create(owner(), applicationId,
                new ApplicationKeyCreateCommand("生产接入", List.of("127.0.0.1"),
                        null, 60, 30_000L));
        assertThat(issued.keyValue()).startsWith("lai_");
        assertThat(service.list(owner(), applicationId)).singleElement()
                .satisfies(key -> {
                    assertThat(key.maskedValue()).startsWith("lai_****");
                    assertThat(key.status()).isEqualTo("ACTIVE");
                    assertThat(key.rotationGeneration()).isEqualTo(1);
                });

        AccessTokenAuthService auth = new AccessTokenAuthService(
                dataSource, new JdbcAccessCredentialRepository(), new JdbcAliasRepository(),
                tokenService, Clock.fixed(Instant.parse("2026-09-08T09:00:00Z"), ZoneOffset.UTC),
                false, applications, keys);
        var principal = auth.authenticate(issued.keyValue(), "127.0.0.1");
        assertThat(principal.application()).isEqualTo("service-desk");
        assertThat(principal.applicationKeyId()).isEqualTo(issued.keyId());
        assertThat(principal.rpm()).isEqualTo(60);
        assertThat(principal.aliasAllowed("ungranted-model")).isFalse();

        var disabled = service.changeStatus(owner(), applicationId, UUID.fromString(issued.keyId()),
                new ApplicationKeyStatusCommand("DISABLED", issued.version(), "暂停接入排查"));
        assertThat(disabled.entity().status()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> auth.authenticate(issued.keyValue(), "127.0.0.1"))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.ACCESS_TOKEN_INVALID);

        assertThatThrownBy(() -> service.rotate(owner(), applicationId,
                UUID.fromString(issued.keyId()),
                new ApplicationKeyRotateCommand(disabled.version(), "停用状态尝试轮换")))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.CONFIG_FIELD_IMMUTABLE);

        var enabled = service.changeStatus(owner(), applicationId, UUID.fromString(issued.keyId()),
                new ApplicationKeyStatusCommand("ACTIVE", disabled.version(), "排查完成恢复"));
        assertThat(enabled.entity().status()).isEqualTo("ACTIVE");
        assertThat(auth.authenticate(issued.keyValue(), "127.0.0.1").application())
                .isEqualTo("service-desk");

        var rotated = service.rotate(owner(), applicationId, UUID.fromString(issued.keyId()),
                new ApplicationKeyRotateCommand(enabled.version(), "季度轮换"));
        assertThat(rotated.rotationGeneration()).isEqualTo(2);
        assertThatThrownBy(() -> auth.authenticate(issued.keyValue(), "127.0.0.1"))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.ACCESS_TOKEN_INVALID);
        assertThat(auth.authenticate(rotated.keyValue(), "127.0.0.1").application())
                .isEqualTo("service-desk");

        service.revoke(owner(), applicationId, UUID.fromString(issued.keyId()),
                new ApplicationKeyRevokeCommand(rotated.version(), "接入下线"));
        assertThatThrownBy(() -> auth.authenticate(rotated.keyValue(), "127.0.0.1"))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    void rejectsKeyLimitsLooserThanApplicationPolicy() {
        assertThatThrownBy(() -> service.create(owner(), applicationId,
                new ApplicationKeyCreateCommand("越界密钥", List.of(), null, 101, null)))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
    }

    private static RequestContext admin() {
        return context("admin", Roles.SYSTEM_ADMIN);
    }

    private static RequestContext owner() {
        return context("owner-1", Roles.APPLICATION_OWNER);
    }

    private static RequestContext context(String user, String role) {
        return new RequestContext(AuthContext.authenticated(user, user, Set.of(role), List.of()),
                "req-key", "127.0.0.*");
    }
}
