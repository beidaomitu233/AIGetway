package com.lightai.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.application.ApplicationImpactCommand;
import com.lightai.client.application.ApplicationImpactView;
import com.lightai.client.application.ApplicationModelConstraint;
import com.lightai.client.application.ApplicationModelOptionView;
import com.lightai.client.application.ApplicationModelsUpdateCommand;
import com.lightai.client.application.ApplicationQuotaAdjustmentCommand;
import com.lightai.client.application.ApplicationQuotaResetCommand;
import com.lightai.client.application.ApplicationQuotaUpdateCommand;
import com.lightai.client.application.ApplicationStatusCommand;
import com.lightai.client.application.ApplicationUpdateCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Roles;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.alias.AliasRecord;
import com.lightai.storage.alias.CandidateRecord;
import com.lightai.storage.alias.JdbcAliasRepository;
import com.lightai.storage.alias.JdbcCandidateRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

class ApplicationServiceTest {

    private ApplicationService service;
    private DataSource dataSource;
    private JdbcAliasRepository aliases;
    private JdbcTemplate jdbc;
    private volatile ConfigSnapshotPort.ActiveSnapshot snapshot =
            new ConfigSnapshotPort.ActiveSnapshot(1, List.of());

    @BeforeEach
    void setUp() {
        JdbcDataSource jdbcDataSource = new JdbcDataSource();
        jdbcDataSource.setURL("jdbc:h2:mem:application_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbcDataSource.setUser("sa");
        dataSource = jdbcDataSource;
        jdbc = new JdbcTemplate(jdbcDataSource);
        new DefaultSchemaMigrator(dataSource).migrate();
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        transactions.afterPropertiesSet();
        Clock clock = Clock.fixed(Instant.parse("2026-09-08T09:00:00Z"), ZoneOffset.UTC);
        AuditService audits = new AuditService(new JdbcAuditRepository(), dataSource,
                transactions, (record, cause) -> { });
        aliases = new JdbcAliasRepository();
        service = new ApplicationService(dataSource, new JdbcApplicationRepository(),
                aliases, audits, transactions,
                new PageResultFactory(clock), clock, "STANDALONE_SERVER", snapshotPort());
    }

    /** 测试快照端口：返回可变的 ActiveSnapshot，供 model-options 与影响预览使用。 */
    private ConfigSnapshotPort snapshotPort() {
        return () -> snapshot;
    }

    /** 插入启用且存在启用候选的虚拟模型（BE-P20-003 可路由口径）。 */
    private void insertRoutableModel(UUID id, String code) throws Exception {
        try (var connection = dataSource.getConnection()) {
            aliases.insert(connection, new AliasRecord(
                    id, code, code + " 名称", null,
                    "WEIGHTED_RANDOM", true, 1L, null, null));
            new JdbcCandidateRepository().insert(connection, new CandidateRecord(
                    UUID.randomUUID(), id, UUID.randomUUID(), UUID.randomUUID(),
                    10, 1, true, 1L, null, null));
        }
    }

    @Test
    void createsApplicationWithQuotaThenSupportsOwnerScopeAndLifecycle() {
        var created = service.create(admin(), new ApplicationCreateCommand(
                "order-assistant", "订单智能助手", "供应链", "owner-1", "张三",
                "PROD", "订单系统 AI 接入", "ACTIVE", 1_000_000L, "500.25",
                "CNY", 120, 100_000L, "MONTH", null, null, List.of()));

        assertThat(created.entity()).isNotNull();
        assertThat(created.entity().quota().amountLimit()).isEqualTo("500.25");
        assertThat(created.entity().quota().tokensUsed()).isEqualTo("0");
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
    void rejectsDuplicateApplicationCodeWith409ConflictCode() {
        service.create(admin(), new ApplicationCreateCommand(
                "dup-app", "首个应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        assertThatThrownBy(() -> service.create(admin(), new ApplicationCreateCommand(
                "dup-app", "重复编码应用", null, "owner-2", "李四", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of())))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.DUPLICATE_APPLICATION_CODE);
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
    void adminsAuthorizeModelsWhileOwnersCanOnlyTighten() throws Exception {
        UUID modelId = UUID.randomUUID();
        insertRoutableModel(modelId, "customer-chat");
        var created = service.create(admin(), new ApplicationCreateCommand(
                "customer-service", "客服系统", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", 1_000L, "100", "CNY", 60, 10_000L,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());

        var quotaUpdated = service.updateQuota(admin(), applicationId,
                new ApplicationQuotaUpdateCommand(
                        2_000L, "250.50", "CNY", 120, 20_000L,
                        "LIFECYCLE", null, null, Long.parseLong(created.entity().quota().version()),
                        "提升生产额度", "put-quota-1"));
        assertThat(quotaUpdated.entity().quota().tokenLimit()).isEqualTo("2000");
        assertThat(quotaUpdated.entity().quota().amountLimit()).isEqualTo("250.5");
        assertThat(quotaUpdated.entity().quota().version()).isEqualTo("2");

        var modelsUpdated = service.updateModels(admin(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(modelId.toString()), List.of(), created.version(), "授权客服模型"));
        assertThat(modelsUpdated.entity().models())
                .filteredOn(item -> item.enabled())
                .extracting(item -> item.virtualModelCode())
                .containsExactly("customer-chat");
        assertThat(modelsUpdated.entity().version()).isEqualTo(created.version() + 1);

        // 应用负责人无显式可授权集合：扩展授权默认拒绝（BE-P20-003）
        UUID extraModel = UUID.randomUUID();
        insertRoutableModel(extraModel, "extra-chat");
        assertThatThrownBy(() -> service.updateModels(owner(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(modelId.toString(), extraModel.toString()), List.of(),
                        modelsUpdated.version(), "负责人越权扩展")))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.ACCESS_DENIED);

        // 收紧已有授权（禁用）允许
        var tightened = service.updateModels(owner(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(), List.of(), modelsUpdated.version(), "负责人收紧授权"));
        assertThat(tightened.entity().models())
                .allSatisfy(item -> assertThat(item.enabled()).isFalse());

        assertThatThrownBy(() -> service.updateModels(admin(), applicationId,
                new ApplicationModelsUpdateCommand(List.of(), List.of(), created.version(), "撤销授权")))
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
                Long.parseLong(created.entity().quota().version()));

        var adjusted = service.adjustQuota(owner(), applicationId, command);
        assertThat(adjusted.entity().quota().tokenLimit()).isEqualTo("1500");
        assertThat(adjusted.entity().quota().version()).isEqualTo("2");

        var replayed = service.adjustQuota(owner(), applicationId, command);
        assertThat(replayed.entity().quota().tokenLimit()).isEqualTo("1500");
        assertThat(replayed.entity().quota().version()).isEqualTo("2");
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
    void allowsLoweringQuotaBelowCommittedUsageAndBlocksAdmission() {
        var created = service.create(admin(), new ApplicationCreateCommand(
                "lower-quota", "降额应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", 1_000L, "100", "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());
        jdbc.update("UPDATE application_quota_policy SET tokens_used=900, amount_used=? "
                        + "WHERE application_id=?", new BigDecimal("80"), applicationId.toString());

        // BE-P20-004/PRD 4.5：允许降到已用+预占以下，保存成功并阻止新准入
        var lowered = service.updateQuota(admin(), applicationId,
                new ApplicationQuotaUpdateCommand(
                        500L, "50", "CNY", null, null,
                        "LIFECYCLE", null, null, Long.parseLong(created.entity().quota().version()),
                        "预算收紧", "lower-quota-1"));
        assertThat(lowered.entity().quota().tokenLimit()).isEqualTo("500");
        assertThat(lowered.entity().quota().tokensRemaining()).isEqualTo("0");
        assertThat(lowered.entity().quota().amountRemaining()).isEqualTo("0");
        assertThat(lowered.entity().quota().admissionBlocked()).isTrue();

        var viewed = service.quota(admin(), applicationId);
        assertThat(viewed.tokensRemaining()).isEqualTo("0");
        assertThat(viewed.amountRemaining()).isEqualTo("0");
        assertThat(viewed.admissionBlocked()).isTrue();
        assertThat(viewed.version()).isEqualTo("2");
    }

    @Test
    void updateQuotaReplaysIdempotentlyViaPolicyLedgerRow() {
        var created = service.create(admin(), new ApplicationCreateCommand(
                "policy-idempotent", "策略幂等应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", 1_000L, "100", "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());
        var command = new ApplicationQuotaUpdateCommand(
                2_000L, "200", "CNY", null, null,
                "LIFECYCLE", null, null, Long.parseLong(created.entity().quota().version()),
                "年度预算调整", "policy-ticket-1");

        var applied = service.updateQuota(admin(), applicationId, command);
        assertThat(applied.entity().quota().tokenLimit()).isEqualTo("2000");
        var replayed = service.updateQuota(admin(), applicationId, command);
        assertThat(replayed.entity().quota().tokenLimit()).isEqualTo("2000");
        assertThat(replayed.entity().quota().version()).isEqualTo("2");

        assertThat(service.listAdjustments(admin(), applicationId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.dimension()).isEqualTo("POLICY");
                    assertThat(item.reason()).isEqualTo("年度预算调整");
                });

        assertThatThrownBy(() -> service.updateQuota(admin(), applicationId,
                new ApplicationQuotaUpdateCommand(
                        3_000L, "300", "CNY", null, null,
                        "LIFECYCLE", null, null, 2L,
                        "另一项调整", "policy-ticket-1")))
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
        jdbc.update("UPDATE application_quota_policy SET tokens_used=250, tokens_reserved=20, "
                        + "amount_used=?, amount_reserved=? WHERE application_id=?",
                new BigDecimal("12.5"), new BigDecimal("1.5"), applicationId.toString());
        var command = new ApplicationQuotaResetCommand(
                "TOKEN_USAGE", "新核算周期人工重置", "quota-reset", "reset-ticket-1",
                Long.parseLong(created.entity().quota().version()));

        var reset = service.resetQuotaUsage(owner(), applicationId, command);
        assertThat(reset.entity().quota().tokensUsed()).isEqualTo("0");
        assertThat(reset.entity().quota().tokensReserved()).isEqualTo("20");
        assertThat(reset.entity().quota().amountUsed()).isEqualTo("12.5");
        assertThat(reset.entity().quota().version()).isEqualTo("2");

        var replayed = service.resetQuotaUsage(owner(), applicationId, command);
        assertThat(replayed.entity().quota().version()).isEqualTo("2");
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
        assertThat(amountReset.entity().quota().version()).isEqualTo("3");
        assertThat(service.listAdjustments(owner(), applicationId))
                .extracting(item -> item.dimension())
                .containsExactly("AMOUNT_USAGE_RESET", "TOKEN_USAGE_RESET");
    }

    @Test
    void appliesApplicationModelConstraintsAndRejectsOutOfScopeTargets() throws Exception {
        UUID granted = UUID.randomUUID();
        UUID notGranted = UUID.randomUUID();
        insertRoutableModel(granted, "constrained-chat");
        insertRoutableModel(notGranted, "unlisted-chat");
        var created = service.create(admin(), new ApplicationCreateCommand(
                "constraint-app", "参数上限应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());

        // 管理员先授权；负责人随后只能收紧参数
        var grantedUpdate = service.updateModels(admin(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(granted.toString()), List.of(), created.version(), "授权客服模型"));

        var updated = service.updateModels(owner(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(granted.toString()),
                        List.of(new ApplicationModelConstraint(granted.toString(), 512, false)),
                        grantedUpdate.version(), "收紧客服模型参数上限"));

        var permission = updated.entity().models().stream()
                .filter(item -> item.virtualModelId().equals(granted.toString()))
                .findFirst().orElseThrow();
        assertThat(permission.maxOutputTokens()).isEqualTo(512);
        assertThat(permission.allowStream()).isFalse();

        assertThatThrownBy(() -> service.updateModels(owner(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(granted.toString()),
                        List.of(new ApplicationModelConstraint(notGranted.toString(), 128, null)),
                        updated.version(), "给未授权模型配置上限")))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);

        assertThatThrownBy(() -> service.updateModels(owner(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(granted.toString()),
                        List.of(new ApplicationModelConstraint(granted.toString(), 0, null)),
                        updated.version(), "非法上限")))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
    }

    @Test
    void authorizationRequiresRoutableModelWithEnabledCandidate() throws Exception {
        UUID orphan = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            aliases.insert(connection, new AliasRecord(
                    orphan, "orphan-chat", "无候选模型", null,
                    "WEIGHTED_RANDOM", true, 1L, null, null));
        }
        var created = service.create(admin(), new ApplicationCreateCommand(
                "routable-check", "可路由校验应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());

        assertThatThrownBy(() -> service.updateModels(admin(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(orphan.toString()), List.of(), created.version(), "授权无候选模型")))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
    }

    @Test
    void listProvidesFiltersBudgetStatusAnd24hSummaries() throws Exception {
        UUID modelId = UUID.randomUUID();
        insertRoutableModel(modelId, "summary-chat");
        var exhausted = service.create(admin(), new ApplicationCreateCommand(
                "exhausted-app", "耗尽应用", "供应链", "owner-1", "张三", "PROD", null,
                "ACTIVE", 1_000L, "100", "CNY", null, null,
                "LIFECYCLE", null, null, List.of(modelId.toString())));
        service.create(admin(), new ApplicationCreateCommand(
                "normal-app", "正常应用", "供应链", "owner-1", "张三", "PROD", null,
                "ACTIVE", 1_000_000L, "1000", "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        service.create(admin(), new ApplicationCreateCommand(
                "unlimited-app", "无限应用", "研发部", "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        jdbc.update("UPDATE application_quota_policy SET tokens_used=1000 WHERE application_id=?",
                exhausted.id());

        var exhaustedOnly = service.list(admin(), Map.of("budget_status", "EXHAUSTED"));
        assertThat(exhaustedOnly.items()).extracting(item -> item.code())
                .containsExactly("exhausted-app");
        var normalOnly = service.list(admin(), Map.of("budget_status", "NORMAL"));
        assertThat(normalOnly.items()).extracting(item -> item.code())
                .containsExactly("normal-app");
        var unlimitedOnly = service.list(admin(), Map.of("budget_status", "UNLIMITED"));
        assertThat(unlimitedOnly.items()).extracting(item -> item.code())
                .containsExactly("unlimited-app");
        var supplyChain = service.list(admin(), Map.of("department", "供应链"));
        assertThat(supplyChain.items()).hasSize(2);

        var rows = service.list(admin(), Map.of("keyword", "exhausted"));
        assertThat(rows.items()).singleElement().satisfies(item -> {
            assertThat(item.budgetStatus()).isEqualTo("EXHAUSTED");
            assertThat(item.tokenLimit()).isEqualTo("1000");
            assertThat(item.tokensUsed()).isEqualTo("1000");
            assertThat(item.version()).isEqualTo("1");
            assertThat(item.modelCount()).isEqualTo(1L);
            assertThat(item.requests24h()).isEqualTo("0");
            assertThat(item.successRate24h()).isNull();
            // 默认排序 last_called_at desc：全部为空时按 id 稳定
            assertThat(item.lastCalledAt()).isNull();
        });
    }

    @Test
    void listAggregates24hSuccessRateFromTraces() throws Exception {
        var created = service.create(admin(), new ApplicationCreateCommand(
                "trace-app", "调用统计应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        insertTrace("trace-app", "t-1", "SUCCEEDED");
        insertTrace("trace-app", "t-2", "FAILED");

        var rows = service.list(admin(), Map.of("keyword", "trace-app"));
        assertThat(rows.items()).singleElement().satisfies(item -> {
            assertThat(item.requests24h()).isEqualTo("2");
            assertThat(item.successRate24h()).isEqualTo("0.5000");
        });
    }

    private void insertTrace(String applicationCode, String traceId, String status) {
        jdbc.update("INSERT INTO trace (id, created_at, updated_at, trace_id, application, "
                        + "source_mode, config_snapshot_no, status, started_at, deadline_at, currency) "
                        + "VALUES (?, TIMESTAMP WITH TIME ZONE '2026-09-08 08:30:00+00:00', "
                        + "TIMESTAMP WITH TIME ZONE '2026-09-08 08:30:00+00:00', ?, ?, 'STANDALONE_SERVER', "
                        + "1, ?, TIMESTAMP WITH TIME ZONE '2026-09-08 08:30:00+00:00', "
                        + "TIMESTAMP WITH TIME ZONE '2026-09-08 09:30:00+00:00', 'CNY')",
                UUID.randomUUID().toString(), traceId, applicationCode, status);
    }

    @Test
    void archiveIsBlockedWhileActiveReservationsExist() {
        var created = service.create(admin(), new ApplicationCreateCommand(
                "archive-guard", "归档占用应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());
        var disabled = service.changeStatus(admin(), applicationId,
                new ApplicationStatusCommand("DISABLED", created.version(), "停用待归档"));

        String keyId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO application_key (id, created_at, updated_at, version, application_id, "
                        + "name, key_prefix, masked_value, key_digest, digest_version, rotation_generation, "
                        + "status) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, ?, 'k-arch', "
                        + "'lai_0001', 'lai_****0001', X'0033', 1, 1, 'ACTIVE')",
                keyId, applicationId.toString());
        jdbc.update("INSERT INTO budget_reservation (id, created_at, updated_at, request_id, "
                        + "application_id, application_key_id, reserved_tokens, reserved_amount, "
                        + "currency, expires_at, status, terminal_at) VALUES (?, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, 'req-arch-1', ?, ?, 10, 0, 'CNY', "
                        + "DATEADD('MINUTE', 10, CURRENT_TIMESTAMP), 'ACTIVE', NULL)",
                UUID.randomUUID().toString(), applicationId.toString(), keyId);

        assertThatThrownBy(() -> service.changeStatus(admin(), applicationId,
                new ApplicationStatusCommand("ARCHIVED", disabled.version(), "带占用归档")))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.OBJECT_IN_USE);

        jdbc.update("UPDATE budget_reservation SET status='RELEASED' WHERE application_id=?",
                applicationId.toString());
        var archived = service.changeStatus(admin(), applicationId,
                new ApplicationStatusCommand("ARCHIVED", disabled.version(), "排空后归档"));
        assertThat(archived.entity().status()).isEqualTo("ARCHIVED");
    }

    @Test
    void modelOptionsReflectSnapshotAndOperatorAuthorizableSet() throws Exception {
        UUID modelA = UUID.randomUUID();
        UUID modelB = UUID.randomUUID();
        // 授权写入按库内口径校验（启用 + 可路由）；快照目录按已发布口径读取
        insertRoutableModel(modelA, "model-a");
        var created = service.create(admin(), new ApplicationCreateCommand(
                "options-app", "候选目录应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());

        snapshot = new ConfigSnapshotPort.ActiveSnapshot(7, List.of(
                aliasView(modelA, "model-a", 4096L, true, true),
                aliasView(modelB, "model-b", 2048L, false, true),
                aliasView(UUID.randomUUID(), "disabled-model", 1024L, true, false)));

        var trusted = service.modelOptions(admin(), applicationId);
        assertThat(trusted.items()).extracting(ApplicationModelOptionView::code)
                .containsExactly("model-a", "model-b");
        assertThat(trusted.items().get(0).virtualModelId()).isEqualTo(modelA.toString());
        assertThat(trusted.items().get(0).snapshotNo()).isEqualTo("7");
        assertThat(trusted.items().get(0).allowStream()).isTrue();
        assertThat(trusted.items().get(1).allowStream()).isFalse();

        // 负责人尚无授权：可授权集合为空
        assertThat(service.modelOptions(owner(), applicationId).items()).isEmpty();

        // 负责人越权扩展被拒：扩展目标在库内可路由但不在其可授权集合
        UUID extraModel = UUID.randomUUID();
        insertRoutableModel(extraModel, "model-b-db");
        assertThatThrownBy(() -> service.updateModels(owner(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(modelA.toString(), extraModel.toString()), List.of(),
                        created.version(), "负责人越权扩展")))
                .isInstanceOf(LightAiException.class)
                .extracting(error -> ((LightAiException) error).code())
                .isEqualTo(ErrorCode.ACCESS_DENIED);

        // 管理员授权 model-a 后，负责人只能看到已授权模型（可收紧集合）
        var granted = service.updateModels(admin(), applicationId,
                new ApplicationModelsUpdateCommand(
                        List.of(modelA.toString()), List.of(), created.version(), "授权 A"));
        var ownerOptions = service.modelOptions(owner(), applicationId);
        assertThat(ownerOptions.items()).extracting(ApplicationModelOptionView::code)
                .containsExactly("model-a");
        assertThat(granted.entity().version()).isEqualTo(created.version() + 1);
    }

    @Test
    void creationTimeModelOptionsTrimUntrustedOperators() throws Exception {
        UUID modelId = UUID.randomUUID();
        snapshot = new ConfigSnapshotPort.ActiveSnapshot(3, List.of(
                aliasView(modelId, "creator-model", 1024L, true, true)));

        var trusted = service.modelOptionsForCreate(admin());
        assertThat(trusted.items()).extracting(ApplicationModelOptionView::code)
                .containsExactly("creator-model");
        // 非可信身份无显式可授权集合：为准备创建不能读取全部资源
        assertThat(service.modelOptionsForCreate(owner()).items()).isEmpty();
    }

    private static ConfigSnapshotPort.AliasView aliasView(
            UUID modelId, String code, Long maxOutputTokens, boolean supportStream,
            boolean aliasEnabled) {
        return new ConfigSnapshotPort.AliasView(modelId.toString(), code, code + " 名称", aliasEnabled,
                List.of(new ConfigSnapshotPort.CandidateView(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(), "openai",
                        "gpt-test", "gpt-test", 10, 1, true, "cl100k", 128000L, maxOutputTokens,
                        supportStream, true, true, true, true, null, null, null, null, null, null,
                        null, null, null, null, 0, "CNY", "http://localhost", null,
                        1000, 30000, Map.of())));
    }

    @Test
    void impactPreviewReportsAffectedKeysAndRunningRequests() throws Exception {
        UUID modelId = UUID.randomUUID();
        insertRoutableModel(modelId, "impact-chat");
        var created = service.create(admin(), new ApplicationCreateCommand(
                "impact-app", "影响预览应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());

        jdbc.update("INSERT INTO application_key (id, created_at, updated_at, version, application_id, "
                        + "name, key_prefix, masked_value, key_digest, digest_version, rotation_generation, "
                        + "status) VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, ?, 'k1', 'lai_0001', "
                        + "'lai_****0001', X'0011', 1, 1, 'ACTIVE')",
                UUID.randomUUID().toString(), applicationId.toString());
        jdbc.update("INSERT INTO budget_reservation (id, created_at, updated_at, request_id, "
                        + "application_id, application_key_id, reserved_tokens, reserved_amount, "
                        + "currency, expires_at, status, terminal_at) VALUES (?, CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, 'req-impact-1', ?, (SELECT k.id FROM application_key k "
                        + "WHERE k.application_id = ? AND k.status = 'ACTIVE' FETCH FIRST 1 ROWS ONLY), "
                        + "5, 0, 'CNY', DATEADD('MINUTE', 10, CURRENT_TIMESTAMP), 'ACTIVE', NULL)",
                UUID.randomUUID().toString(), applicationId.toString(), applicationId.toString());

        var statusImpact = service.impact(admin(), applicationId,
                new ApplicationImpactCommand(created.version(), "STATUS_CHANGE", "ARCHIVED", null));
        assertThat(statusImpact.affectedKeyCount()).isEqualTo("1");
        assertThat(statusImpact.affectedKeyIds()).hasSize(1);
        assertThat(statusImpact.hasMoreKeys()).isFalse();
        assertThat(statusImpact.runningRequests()).isEqualTo("1");
        assertThat(statusImpact.blockers())
                .extracting(ApplicationImpactView.Blocker::code)
                .containsExactly("OBJECT_IN_USE");

        var modelImpact = service.impact(admin(), applicationId,
                new ApplicationImpactCommand(created.version(), "MODEL_PERMISSION_CHANGE", null,
                        List.of(modelId.toString())));
        assertThat(modelImpact.affectedKeyCount()).isEqualTo("1");
        assertThat(modelImpact.blockers()).isEmpty();
    }

    @Test
    void listsApplicationMembersReadOnlyAndKeepsOwnerScope() {
        var created = service.create(admin(), new ApplicationCreateCommand(
                "member-app", "成员应用", null, "owner-1", "张三", "PROD", null,
                "ACTIVE", null, null, "CNY", null, null,
                "LIFECYCLE", null, null, List.of()));
        UUID applicationId = UUID.fromString(created.id());

        assertThat(service.listMembers(owner(), applicationId))
                .singleElement()
                .satisfies(member -> {
                    assertThat(member.subjectId()).isEqualTo("owner-1");
                    assertThat(member.subjectName()).isEqualTo("张三");
                    assertThat(member.role()).isEqualTo("OWNER");
                });

        assertThatThrownBy(() -> service.listMembers(
                context("other-user", "李四", Roles.APPLICATION_OWNER), applicationId))
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
