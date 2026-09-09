package com.lightai.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.application.ApplicationModelsUpdateCommand;
import com.lightai.client.application.ApplicationQuotaAdjustmentCommand;
import com.lightai.client.application.ApplicationQuotaResetCommand;
import com.lightai.client.application.ApplicationQuotaUpdateCommand;
import com.lightai.client.application.ApplicationStatusCommand;
import com.lightai.client.application.ApplicationUpdateCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Roles;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.alias.AliasRecord;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.application.JdbcApplicationRepository;
import com.lightai.storage.audit.JdbcAuditRepository;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.math.BigDecimal;
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
import javax.sql.DataSource;

class ApplicationServiceTest {

    private ApplicationService service;
    private DataSource dataSource;
    private JdbcAliasRepository aliases;

    @BeforeEach
    void setUp() {
        JdbcDataSource jdbcDataSource = new JdbcDataSource();
        jdbcDataSource.setURL("jdbc:h2:mem:application_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbcDataSource.setUser("sa");
        dataSource = jdbcDataSource;
        new DefaultSchemaMigrator(dataSource).migrate();
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        transactions.afterPropertiesSet();
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T09:00:00Z"), ZoneOffset.UTC);
        AuditService audits = new AuditService(new JdbcAuditRepository(), dataSource,
                transactions, (record, cause) -> { });
        aliases = new JdbcAliasRepository();
        service = new ApplicationService(dataSource, new JdbcApplicationRepository(),
                aliases, audits, transactions,
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

    @Test
    void updatesQuotaAndModelPermissionsWithIndependentOptimisticVersions() throws Exception {
        UUID modelId = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            aliases.insert(connection, new AliasRecord(
                    modelId, "customer-chat", "客服模型", null,
                    "WEIGHTED_RANDOM", true, 1L, null, null));
        }
        var created = service.create(admin(), new ApplicationCreateCommand(
                "customer-service", "客服系统", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", 1_000L, "100", "CNY", 60, 10_000L,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());

        var quotaUpdated = service.updateQuota(owner(), applicationId,
                new ApplicationQuotaUpdateCommand(
                        2_000L, "250.50", "CNY", 120, 20_000L,
                        "LIFECYCLE", null, null, created.entity().quota().version(),
                        "提升生产额度"));
        assertThat(quotaUpdated.entity().quota().tokenLimit()).isEqualTo(2_000L);
        assertThat(quotaUpdated.entity().quota().amountLimit()).isEqualTo("250.5");
        assertThat(quotaUpdated.entity().quota().version()).isEqualTo(2L);

        var modelsUpdated = service.updateModels(owner(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(modelId.toString()), created.version(), "授权客服模型"));
        assertThat(modelsUpdated.entity().models())
                .filteredOn(item -> item.enabled())
                .extracting(item -> item.virtualModelCode())
                .containsExactly("customer-chat");
        assertThat(modelsUpdated.entity().version()).isEqualTo(created.version() + 1);

        assertThatThrownBy(() -> service.updateModels(owner(), applicationId,
                new ApplicationModelsUpdateCommand(List.of(), created.version(), "撤销授权")))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.CONFIG_VERSION_CONFLICT);
    }

    @Test
    void adjustsQuotaIdempotentlyAndRejectsKeyReuseWithDifferentPayload() {
        var created = service.create(admin(), new ApplicationCreateCommand(
                "quota-adjustment", "额度调整应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", 1_000L, "100", "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());
        var command = new ApplicationQuotaAdjustmentCommand(
                "TOKEN_LIMIT", "500", "临时扩容", "ticket-20260908-1",
                created.entity().quota().version());

        var adjusted = service.adjustQuota(owner(), applicationId, command);
        assertThat(adjusted.entity().quota().tokenLimit()).isEqualTo(1_500L);
        assertThat(adjusted.entity().quota().version()).isEqualTo(2L);

        var replayed = service.adjustQuota(owner(), applicationId, command);
        assertThat(replayed.entity().quota().tokenLimit()).isEqualTo(1_500L);
        assertThat(replayed.entity().quota().version()).isEqualTo(2L);
        assertThat(service.listAdjustments(owner(), applicationId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.dimension()).isEqualTo("TOKEN_LIMIT");
                    assertThat(item.beforeValue()).isEqualTo("1000");
                    assertThat(item.deltaValue()).isEqualTo("500");
                    assertThat(item.afterValue()).isEqualTo("1500");
                });

        assertThatThrownBy(() -> service.adjustQuota(owner(), applicationId,
                new ApplicationQuotaAdjustmentCommand(
                        "TOKEN_LIMIT", "600", "另一笔调整", "ticket-20260908-1", 2L)))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void resetsUsageWithExplicitConfirmationAndKeepsHistoryIdempotently() throws Exception {
        var created = service.create(admin(), new ApplicationCreateCommand(
                "quota-reset", "额度重置应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", 1_000L, "100", "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE application_quota_policy SET tokens_used=?, tokens_reserved=?, "
                             + "amount_used=?, amount_reserved=? "
                             + "WHERE application_id=?")) {
            statement.setLong(1, 250L);
            statement.setLong(2, 20L);
            statement.setBigDecimal(3, new BigDecimal("12.5"));
            statement.setBigDecimal(4, new BigDecimal("1.5"));
            statement.setString(5, applicationId.toString());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        var command = new ApplicationQuotaResetCommand(
                "TOKEN_USAGE", "新核算周期人工重置", "quota-reset", "reset-ticket-1",
                created.entity().quota().version());

        var reset = service.resetQuotaUsage(owner(), applicationId, command);
        assertThat(reset.entity().quota().tokensUsed()).isZero();
        assertThat(reset.entity().quota().tokensReserved()).isEqualTo(20L);
        assertThat(reset.entity().quota().amountUsed()).isEqualTo("12.5");
        assertThat(reset.entity().quota().version()).isEqualTo(2L);

        var replayed = service.resetQuotaUsage(owner(), applicationId, command);
        assertThat(replayed.entity().quota().version()).isEqualTo(2L);
        assertThat(service.listAdjustments(owner(), applicationId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.dimension()).isEqualTo("TOKEN_USAGE_RESET");
                    assertThat(item.beforeValue()).isEqualTo("250");
                    assertThat(item.deltaValue()).isEqualTo("-250");
                    assertThat(item.afterValue()).isEqualTo("0");
                });

        assertThatThrownBy(() -> service.resetQuotaUsage(owner(), applicationId,
                new ApplicationQuotaResetCommand(
                        "AMOUNT_USAGE", "另一项重置", "quota-reset", "reset-ticket-1", 2L)))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThatThrownBy(() -> service.resetQuotaUsage(owner(), applicationId,
                new ApplicationQuotaResetCommand(
                        "AMOUNT_USAGE", "金额重置", "错误编码", "reset-ticket-2", 2L)))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);

        var amountReset = service.resetQuotaUsage(owner(), applicationId,
                new ApplicationQuotaResetCommand(
                        "AMOUNT_USAGE", "金额核算重置", "quota-reset", "reset-ticket-2", 2L));
        assertThat(amountReset.entity().quota().amountUsed()).isEqualTo("0");
        assertThat(amountReset.entity().quota().amountReserved()).isEqualTo("1.5");
        assertThat(amountReset.entity().quota().version()).isEqualTo(3L);
        assertThat(service.listAdjustments(owner(), applicationId))
                .extracting(item -> item.dimension())
                .containsExactly("AMOUNT_USAGE_RESET", "TOKEN_USAGE_RESET");
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
