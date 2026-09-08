package com.lightai.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.application.ApplicationStatusCommand;
import com.lightai.client.application.ApplicationUpdateCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Roles;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.alias.JdbcAliasRepository;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ApplicationServiceTest {

    private ApplicationService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:application_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        new DefaultSchemaMigrator(dataSource).migrate();
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        transactions.afterPropertiesSet();
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T09:00:00Z"), ZoneOffset.UTC);
        AuditService audits = new AuditService(new JdbcAuditRepository(), dataSource,
                transactions, (record, cause) -> { });
        service = new ApplicationService(dataSource, new JdbcApplicationRepository(),
                new JdbcAliasRepository(), audits, transactions,
                new PageResultFactory(clock), clock, "STANDALONE_SERVER");
    }

    @Test
    void createsApplicationWithQuotaThenSupportsOwnerScopeAndLifecycle() {
        var created = service.create(admin(), new ApplicationCreateCommand(
                "order-assistant", "订单智能助手", "供应链", "owner-1", "张三",
                "PROD", "订单系统 AI 接入", "ACTIVE", 1_000_000L, "500.25",
                "CNY", 120, 100_000L, "MONTH", null, null, List.of()));

        assertThat(created.entity()).isNotNull();
        assertThat(created.entity().quota().amountLimit()).isEqualTo("500.25");
        assertThat(created.entity().quota().tokensUsed()).isZero();
        assertThat(service.list(owner(), Map.of()).items())
                .extracting(item -> item.code()).containsExactly("order-assistant");

        UUID id = UUID.fromString(created.id());
        var updated = service.update(owner(), id, new ApplicationUpdateCommand(
                "订单助手生产环境", "供应链", "owner-1", "张三", "PROD",
                "订单与售后场景", created.version()));
        assertThat(updated.entity().name()).isEqualTo("订单助手生产环境");

        var disabled = service.changeStatus(owner(), id,
                new ApplicationStatusCommand("DISABLED", updated.version(), "生产变更窗口"));
        assertThat(disabled.entity().status()).isEqualTo("DISABLED");
        var archived = service.changeStatus(owner(), id,
                new ApplicationStatusCommand("ARCHIVED", disabled.version(), "业务系统下线"));
        assertThat(archived.entity().status()).isEqualTo("ARCHIVED");
    }

    @Test
    void rejectsInvalidQuotaAndApplicationOutsideOwnerScope() {
        assertThatThrownBy(() -> service.create(admin(), new ApplicationCreateCommand(
                "bad-app", "不合法应用", null, "owner-2", "李四", "PROD", null,
                "ACTIVE", 0L, null, "CNY", null, null,
                "MONTH", null, null, List.of())))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);

        var created = service.create(admin(), new ApplicationCreateCommand(
                "finance-assistant", "财务助手", null, "owner-2", "李四", "PROD", null,
                "DISABLED", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        assertThatThrownBy(() -> service.detail(owner(), UUID.fromString(created.id())))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    private static RequestContext admin() {
        return context("admin-1", "系统管理员", Roles.SYSTEM_ADMIN);
    }

    private static RequestContext owner() {
        return context("owner-1", "张三", Roles.APPLICATION_OWNER);
    }

    private static RequestContext context(String userId, String displayName, String role) {
        return new RequestContext(AuthContext.authenticated(
                userId, displayName, Set.of(role), List.of()), "req-application", "127.0.0.*");
    }
}
