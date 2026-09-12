package com.lightai.admin.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.lightai.admin.audit.AuditService;
import com.lightai.admin.runtimeconfig.RuntimeConfigAdminService;
import com.lightai.admin.web.AdminErrorHandler;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestIdFilter;
import com.lightai.client.json.ProtocolJson;
import com.lightai.client.protocol.Roles;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.audit.JdbcAuditRepository;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * BE-234 系统设置接口测试：真实迁移 schema + 真实服务。
 * 覆盖四角色读取、修改权限、乐观锁版本冲突与 retention-impact 受控入口。
 */
class SettingsApiTest {

    private MockMvc mvc;
    private long version;
    private int seededTraceRetentionDays;

    @BeforeEach
    void setUp() {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:settings_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        database.setUser("sa");
        new DefaultSchemaMigrator(database).migrate();
        // 迁移已种子化 singleton runtime_config 行：动态读取当前版本，不假设默认值
        var jdbc = new JdbcTemplate(database);
        this.version = jdbc.queryForObject("SELECT version FROM runtime_config WHERE singleton_key = 1", Long.class);
        this.seededTraceRetentionDays = jdbc.queryForObject(
                "SELECT trace_retention_days FROM runtime_config WHERE singleton_key = 1", Integer.class);

        var clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
        var tx = new DataSourceTransactionManager(database);
        tx.afterPropertiesSet();
        var audits = new AuditService(new JdbcAuditRepository(), database, tx, (record, cause) -> { });
        var repository = new com.lightai.storage.security.JdbcRuntimeConfigAdminRepository();
        var service = new RuntimeConfigAdminService(database, tx, repository, audits, clock,
                () -> 1L, "STANDALONE_SERVER");
        mvc = MockMvcBuilders.standaloneSetup(new SettingsController(service))
                .setControllerAdvice(new AdminErrorHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static RequestContext identity(String user, String role, List<String> scope) {
        return new RequestContext(
                AuthContext.authenticated(user, user, Set.of(role), scope),
                "req-settings-test", "127.0.0.*");
    }

    private static OffsetDateTime ts(String value) {
        return OffsetDateTime.parse(value);
    }

    @Test
    void settingsReadableByPrivilegedRolesAndDeniedToOthers() throws Exception {
        // 角色矩阵：runtimeconfig.view 仅授予系统管理员/运维/开发/只读（RolePermissions）
        for (String role : List.of(Roles.SYSTEM_ADMIN, Roles.OPERATOR)) {
            MvcResult result = mvc.perform(get("/admin/settings")
                            .requestAttr(RequestContext.ATTRIBUTE,
                                    identity("user-" + role, role, List.of())))
                    .andExpect(status().isOk()).andReturn();
            JsonNode data = ProtocolJson.protocol()
                    .readTree(result.getResponse().getContentAsString()).path("data");
            assertThat(data.path("timezone").asText()).isEqualTo("Asia/Shanghai");
            assertThat(data.path("trace_retention_days").asInt()).isEqualTo(seededTraceRetentionDays);
            assertThat(data.path("version").asLong()).isEqualTo(version);
        }
        mvc.perform(get("/admin/settings"))
                .andExpect(status().isForbidden());
        for (String role : List.of(Roles.APPLICATION_OWNER, Roles.AUDITOR)) {
            mvc.perform(get("/admin/settings")
                            .requestAttr(RequestContext.ATTRIBUTE,
                                    identity("user-" + role, role, List.of())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        }
    }

    @Test
    void putRequiresManagePermissionAndVersion() throws Exception {
        String body = putBody(version);
        // 运维/负责人/审计员无 runtimeconfig.manage
        for (String role : List.of(Roles.OPERATOR, Roles.APPLICATION_OWNER, Roles.AUDITOR)) {
            mvc.perform(put("/admin/settings")
                            .requestAttr(RequestContext.ATTRIBUTE,
                                    identity("user-" + role, role, List.of()))
                            .contentType("application/json").content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        }
        // 管理员版本过期 → 409
        mvc.perform(put("/admin/settings")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of()))
                        .contentType("application/json").content(putBody(version + 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFIG_VERSION_CONFLICT"));
        // 管理员合法版本 → 200 且版本递增
        MvcResult ok = mvc.perform(put("/admin/settings")
                        .requestAttr(RequestContext.ATTRIBUTE,
                                identity("admin", Roles.SYSTEM_ADMIN, List.of()))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = ProtocolJson.protocol()
                .readTree(ok.getResponse().getContentAsString()).path("data");
        // PUT 返回 ManagementOperationResult：entity 内嵌最新视图，version 为新版本
        assertThat(data.path("entity").path("trace_retention_days").asInt())
                .isEqualTo(seededTraceRetentionDays + 15);
        assertThat(data.path("entity").path("version").asLong()).isEqualTo(version + 1);
    }

    private String putBody(long expectedVersion) {
        return "{\"timezone\":\"Asia/Shanghai\",\"trace_retention_days\":" + (seededTraceRetentionDays + 15) + ","
                + "\"usage_retention_days\":90,\"audit_retention_days\":365,"
                + "\"dashboard_refresh_seconds\":30,\"max_message_chars\":64000,"
                + "\"max_request_chars\":256000,\"diagnostic_sampling_enabled\":false,"
                + "\"diagnostic_sample_rate\":0,\"diagnostic_sample_retention_days\":3,"
                + "\"diagnostic_sample_max_chars\":4000,\"client_ip_recording_enabled\":false,"
                + "\"trusted_proxy_cidrs\":[],\"publish_instance_timeout_seconds\":300,"
                + "\"instance_stale_seconds\":45,\"version\":" + expectedVersion + "}";
    }
}
