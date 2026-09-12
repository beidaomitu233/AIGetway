package com.lightai.admin.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.lightai.admin.web.AdminErrorHandler;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestIdFilter;
import com.lightai.client.json.ProtocolJson;
import com.lightai.client.protocol.Roles;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import com.lightai.storage.trace.JdbcObservationConfigReader;
import com.lightai.storage.trace.JdbcUsageAdjustmentRepository;
import com.lightai.storage.trace.JdbcUsageAggregateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * BE-232 额度流水接口测试：真实迁移 schema + 真实仓储。
 * 覆盖两源合并排序、账本事件类型解析、身份数据范围、source 筛选与鉴权。
 */
class UsageAdjustmentApiTest {

    private static final String APP_A = UUID.randomUUID().toString();
    private static final String APP_B = UUID.randomUUID().toString();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:usage_adj_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        database.setUser("sa");
        new DefaultSchemaMigrator(database).migrate();
        insertFixtures(database);

        var clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
        var aggregates = new JdbcUsageAggregateRepository();
        var configReader = new JdbcObservationConfigReader();
        var usageService = new UsageService(database, aggregates, configReader, clock);
        var exportService = new UsageExportService(database, aggregates, usageService);
        var adjustmentService = new UsageAdjustmentService(database,
                new JdbcUsageAdjustmentRepository(), clock);
        var controller = new UsageController(usageService, exportService, adjustmentService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AdminErrorHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static RequestContext identity(String user, String role, List<String> scope) {
        return new RequestContext(
                AuthContext.authenticated(user, user, java.util.Set.of(role), scope),
                "req-usage-test", "127.0.0.*");
    }

    private void insertFixtures(javax.sql.DataSource dataSource) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        insertApplication(jdbc, APP_A, "app-a");
        insertApplication(jdbc, APP_B, "app-b");
        // 人工调整：app-a 两条（不同时间），app-b 一条
        jdbc.update("INSERT INTO quota_adjustment (id, created_at, application_id, dimension, "
                        + "before_value, delta_value, after_value, reason, effective_at, operator_id, "
                        + "idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), ts("2026-09-11T01:00:00Z"), APP_A, "TOKEN_LIMIT",
                1000, 500, 1500, "扩容", ts("2026-09-11T01:00:00Z"), "admin-1", "adj-key-1");
        jdbc.update("INSERT INTO quota_adjustment (id, created_at, application_id, dimension, "
                        + "before_value, delta_value, after_value, reason, effective_at, operator_id, "
                        + "idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), ts("2026-09-11T03:00:00Z"), APP_A, "AMOUNT_LIMIT",
                10.5, 4.5, 15.0, "追加预算", ts("2026-09-11T03:00:00Z"), "admin-1", "adj-key-2");
        jdbc.update("INSERT INTO quota_adjustment (id, created_at, application_id, dimension, "
                        + "before_value, delta_value, after_value, reason, effective_at, operator_id, "
                        + "idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), ts("2026-09-11T02:00:00Z"), APP_B, "TOKEN_USAGE",
                9000, 0, 0, "周期重置", ts("2026-09-11T02:00:00Z"), "admin-2", "adj-key-3");
        // 账本事件：app-a 结算一条、app-b 结算一条
        jdbc.update("INSERT INTO usage_ledger (id, created_at, event_key, request_id, "
                        + "application_id, input_tokens, output_tokens, token_delta, amount_delta, "
                        + "currency, usage_source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), ts("2026-09-11T04:00:00Z"),
                "SETTLEMENT:req-ledger-a", "req-ledger-a", APP_A, 100, 200, 300,
                0.012, "USD", "ACTUAL");
        jdbc.update("INSERT INTO usage_ledger (id, created_at, event_key, request_id, "
                        + "application_id, input_tokens, output_tokens, token_delta, amount_delta, "
                        + "currency, usage_source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), ts("2026-09-11T00:30:00Z"),
                "SETTLEMENT:req-ledger-b", "req-ledger-b", APP_B, 10, 20, 30,
                0.001, "USD", "ESTIMATED");
    }

    private void insertApplication(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                   String id, String code) {
        jdbc.update("INSERT INTO application (id, created_at, updated_at, code, name, owner_id, "
                        + "owner_name, environment, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, ts("2026-09-10T00:00:00Z"), ts("2026-09-10T00:00:00Z"), code,
                "应用 " + code, "owner", "负责人", "TEST", "ACTIVE");
    }

    private static OffsetDateTime ts(String value) {
        return OffsetDateTime.parse(value);
    }

    @Test
    void adjustmentsMergeBothSourcesOrderedByTimeDesc() throws Exception {
        MvcResult result = mvc.perform(get("/admin/usage/adjustments")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of())))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = ProtocolJson.protocol()
                .readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("total").asLong()).isEqualTo(5);
        JsonNode items = data.path("items");
        // 时间倒序：04:00 账本 → 03:00 调整 → 02:00 调整 → 01:00 调整 → 00:30 账本
        assertThat(items.get(0).path("source").asText()).isEqualTo("USAGE_LEDGER");
        assertThat(items.get(0).path("event_type").asText()).isEqualTo("SETTLE");
        assertThat(items.get(0).path("request_id").asText()).isEqualTo("req-ledger-a");
        assertThat(items.get(0).path("amount_delta").asText()).isEqualTo("0.01200000");
        assertThat(items.get(0).path("currency").asText()).isEqualTo("USD");
        assertThat(items.get(0).path("usage_source").asText()).isEqualTo("ACTUAL");
        assertThat(items.get(1).path("source").asText()).isEqualTo("QUOTA_ADJUSTMENT");
        assertThat(items.get(1).path("event_type").asText()).isEqualTo("AMOUNT_LIMIT");
        assertThat(items.get(1).path("delta_value").asText()).isEqualTo("4.50000000");
        assertThat(items.get(1).path("operator_id").asText()).isEqualTo("admin-1");
        assertThat(items.get(1).path("idempotency_key").asText()).isEqualTo("adj-key-2");
    }

    @Test
    void adjustmentsRespectApplicationScope() throws Exception {
        MvcResult result = mvc.perform(get("/admin/usage/adjustments")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("owner", Roles.APPLICATION_OWNER, List.of("app-a"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = ProtocolJson.protocol()
                .readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("total").asLong()).isEqualTo(3);
        for (JsonNode item : data.path("items")) {
            assertThat(item.path("application_code").asText()).isEqualTo("app-a");
        }
    }

    @Test
    void adjustmentsSupportSourceFilterAndRejectInvalidSource() throws Exception {
        MvcResult ledgerOnly = mvc.perform(get("/admin/usage/adjustments")
                        .param("source", "USAGE_LEDGER")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of())))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = ProtocolJson.protocol()
                .readTree(ledgerOnly.getResponse().getContentAsString()).path("data");
        assertThat(data.path("total").asLong()).isEqualTo(2);

        mvc.perform(get("/admin/usage/adjustments")
                        .param("source", "UNKNOWN")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_VALIDATION_FAILED"));
    }
}
