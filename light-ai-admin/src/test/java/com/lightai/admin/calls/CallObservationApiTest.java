package com.lightai.admin.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.trace.TraceDetailService;
import com.lightai.admin.trace.TraceExportService;
import com.lightai.admin.trace.TraceService;
import com.lightai.admin.web.AdminErrorHandler;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestIdFilter;
import com.lightai.client.json.ProtocolJson;
import com.lightai.client.protocol.Roles;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.audit.JdbcAuditRepository;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import com.lightai.storage.trace.JdbcObservationConfigReader;
import com.lightai.storage.trace.JdbcTraceDetailRepository;
import com.lightai.storage.trace.JdbcTraceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * BE-231 接口测试：真实迁移 schema + 真实服务 + MockMvc。
 * 覆盖 request_id 贯穿、应用数据范围、404/403、导出权限与时间窗校验。
 */
class CallObservationApiTest {

    private static final String TRACE_A = "req-app-a-0001";
    private static final String TRACE_B = "req-app-b-0001";

    private JdbcDataSource database;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:call_obs_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        database.setUser("sa");
        new DefaultSchemaMigrator(database).migrate();
        insertFixtures(database);

        var clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
        var tx = new DataSourceTransactionManager(database);
        tx.afterPropertiesSet();
        var audits = new AuditService(new JdbcAuditRepository(), database, tx, (record, cause) -> { });
        var traceRepository = new JdbcTraceRepository();
        var detailRepository = new JdbcTraceDetailRepository();
        var configReader = new JdbcObservationConfigReader();
        var traceService = new TraceService(database, traceRepository,
                new PageResultFactory(clock), clock);
        var detailService = new TraceDetailService(database, traceRepository, detailRepository,
                configReader, audits, clock);
        var exportService = new TraceExportService(database, traceRepository);
        var controller = new CallObservationController(
                new CallObservationService(traceService, detailService), exportService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AdminErrorHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static RequestContext identity(String user, String role, List<String> scope) {
        return new RequestContext(
                AuthContext.authenticated(user, user, java.util.Set.of(role), scope),
                "req-call-test", "127.0.0.*");
    }

    private static OffsetDateTime ts(String value) {
        return OffsetDateTime.parse(value);
    }

    private void insertFixtures(DataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource);
        insertTrace(jdbc, TRACE_A, "app-a", "SUCCEEDED", 2, "2026-09-11T00:00:01Z");
        insertTrace(jdbc, TRACE_B, "app-b", "RUNNING", 1, "2026-09-11T00:00:30Z");
        insertAttempt(jdbc, TRACE_A, 1, "INITIAL", "FAILED", 10, 20);
        insertAttempt(jdbc, TRACE_A, 2, "RETRY", "SUCCEEDED", 30, 50);
        insertAttempt(jdbc, TRACE_B, 1, "INITIAL", "RUNNING", 5, 0);
        // 当前已发布迁移的 recovery_decision 只有旧列（BE-P23-001）：action 由 decision_type 映射
        jdbc.update("INSERT INTO recovery_decision (id, created_at, trace_id, sequence, "
                        + "failed_attempt_sequence, decision_type, target_candidate_id, "
                        + "target_credential_id, reason_code, reason_detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), ts("2026-09-11T00:00:05Z"), TRACE_A, 1,
                1, "RETRY_SAME_KEY", null, null, "UPSTREAM_TIMEOUT", "上游超时，重试同一 Key");
    }

    private void insertTrace(JdbcTemplate jdbc, String traceId, String application,
                             String status, int attemptCount, String startedAt) {
        jdbc.update("INSERT INTO trace (id, created_at, updated_at, trace_id, application, "
                        + "source_mode, config_snapshot_no, requested_stream, status, started_at, "
                        + "deadline_at, queued_ms, attempt_count, retry_count, credential_failover_count, "
                        + "fallback_count, input_tokens, output_tokens, total_tokens, input_cost, "
                        + "output_cost, total_cost, currency, usage_source, request_summary) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), ts("2026-09-11T00:00:00Z"), ts("2026-09-11T00:00:10Z"),
                traceId, application, "STANDALONE_SERVER", 7L, false, status,
                ts(startedAt), ts(startedAt.replace("00:00", "00:01")),
                120, attemptCount, 1, 0, 0, 100, 200, 300, 0.001, 0.002, 0.003,
                "USD", "ACTUAL", "{}");
    }

    private void insertAttempt(JdbcTemplate jdbc, String traceId, int sequence, String type,
                               String status, Integer inputTokens, Integer outputTokens) {
        jdbc.update("INSERT INTO attempt (id, created_at, updated_at, trace_id, sequence, "
                        + "attempt_type, channel_id, upstream_model_id, channel_credential_id, "
                        + "channel_name_snapshot, upstream_model_name_snapshot, model_id_snapshot, "
                        + "channel_credential_name_snapshot, status, started_at, endpoint_host, "
                        + "input_tokens, output_tokens, total_tokens, usage_source) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), ts("2026-09-11T00:00:02Z"), ts("2026-09-11T00:00:09Z"),
                traceId, sequence, type, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "channel-alpha", "gpt-x", "gpt-x", "key-masked-01",
                status, ts("2026-09-11T00:00:02Z"), "api.example.com",
                inputTokens, outputTokens,
                inputTokens == null || outputTokens == null ? 0 : inputTokens + outputTokens,
                "ACTUAL");
    }

    @Test
    void listReturnsRequestIdContractAndScopedRows() throws Exception {
        MvcResult admin = mvc.perform(get("/admin/calls")
                        .param("start_at", "2026-09-10T00:00:00Z")
                        .param("end_at", "2026-09-12T00:00:00Z")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of())))
                .andExpect(status().isOk()).andReturn();
        System.out.println("DBG_BODY=" + admin.getResponse().getContentAsString());
        JsonNode data = ProtocolJson.protocol()
                .readTree(admin.getResponse().getContentAsString()).path("data");
        assertThat(data.path("total").asLong()).isEqualTo(2);
        JsonNode first = data.path("items").get(0);
        assertThat(first.path("request_id").asText()).isEqualTo(TRACE_B);
        assertThat(first.has("trace_id")).isFalse();
        assertThat(first.path("application_code").asText()).isEqualTo("app-b");
        assertThat(first.path("attempt_count").asInt()).isEqualTo(1);
        assertThat(first.has("input_tokens")).isTrue();
        assertThat(first.has("output_tokens")).isTrue();

        // 应用负责人只看到自己应用：范围先于筛选注入
        MvcResult owner = mvc.perform(get("/admin/calls")
                        .param("start_at", "2026-09-10T00:00:00Z")
                        .param("end_at", "2026-09-12T00:00:00Z")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("owner", Roles.APPLICATION_OWNER, List.of("app-a"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode scoped = ProtocolJson.protocol()
                .readTree(owner.getResponse().getContentAsString()).path("data");
        assertThat(scoped.path("total").asLong()).isEqualTo(1);
        assertThat(scoped.path("items").get(0).path("request_id").asText()).isEqualTo(TRACE_A);

        // 范围外审计员：列表按范围过滤，不泄露范围外是否存在数据
        MvcResult outsider = mvc.perform(get("/admin/calls")
                        .param("start_at", "2026-09-10T00:00:00Z")
                        .param("end_at", "2026-09-12T00:00:00Z")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("viewer", Roles.AUDITOR, List.of("app-other"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(ProtocolJson.protocol()
                .readTree(outsider.getResponse().getContentAsString())
                .path("data").path("total").asLong()).isZero();
    }

    @Test
    void detailAssemblesTimelineWithRequestId() throws Exception {
        MvcResult result = mvc.perform(get("/admin/calls/" + TRACE_A)
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of())))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = ProtocolJson.protocol()
                .readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("request_id").asText()).isEqualTo(TRACE_A);
        assertThat(data.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(data.path("attempts").size()).isEqualTo(2);
        assertThat(data.path("recovery_decisions").size()).isEqualTo(1);
        assertThat(data.path("timeline").size()).isGreaterThanOrEqualTo(4);
        // 默认无正文：诊断采样关闭时不返回任何消息内容
        assertThat(data.path("request_summary").path("content_sample_status").asText())
                .isEqualTo("DISABLED");

        // 越权与不存在
        mvc.perform(get("/admin/calls/" + TRACE_B)
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("owner", Roles.APPLICATION_OWNER, List.of("app-a"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        mvc.perform(get("/admin/calls/" + TRACE_B)
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of())))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/calls/req-unknown")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));
    }

    @Test
    void exportRequiresTimeRangeAndPermission() throws Exception {
        // 仅提供 start_at 缺 end_at：解析器要求完整时间窗（缺省会填充最近 1 小时）
        mvc.perform(get("/admin/calls/export")
                        .param("start_at", "2026-09-10T00:00:00Z")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of())))
                .andExpect(status().isBadRequest());

        // StreamingResponseBody 为异步响应，MockMvc 断言响应头；行内容契约由
        // TraceExportService 的白名单列保证（不包含正文与密钥字段）
        mvc.perform(get("/admin/calls/export")
                        .param("start_at", "2026-09-10T00:00:00Z")
                        .param("end_at", "2026-09-12T00:00:00Z")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of())))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse()
                        .getHeader("Content-Type")).contains("text/csv"))
                .andExpect(result -> assertThat(result.getResponse()
                        .getHeader("Content-Disposition")).contains("attachment"));

        mvc.perform(get("/admin/calls/export")
                        .param("start_at", "2026-09-10T00:00:00Z")
                        .param("end_at", "2026-09-12T00:00:00Z")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("dev", Roles.APPLICATION_OWNER, List.of("app-a"))))
                .andExpect(status().isForbidden());
    }
}
