package com.lightai.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.lightai.admin.audit.AuditService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.security.AccessTokenService;
import com.lightai.admin.web.AdminErrorHandler;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestIdFilter;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.json.ProtocolJson;
import com.lightai.client.protocol.Roles;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.spi.auth.AuthContext;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract tests with production services and migrated H2; identity is a test fixture. */
class ApplicationApiContractTest {
    private MockMvc mvc;
    private JdbcDataSource database;
    private String applicationId;

    @BeforeEach
    void setUp() {
        database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:application_api_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        database.setUser("sa");
        new DefaultSchemaMigrator(database).migrate();
        var tx = new DataSourceTransactionManager(database);
        tx.afterPropertiesSet();
        var clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
        var audits = new AuditService(new JdbcAuditRepository(), database, tx, (record, cause) -> { });
        var applications = new JdbcApplicationRepository();
        var service = new ApplicationService(database, applications, new JdbcAliasRepository(),
                audits, tx, new PageResultFactory(clock), clock, "STANDALONE_SERVER",
                () -> new ConfigSnapshotPort.ActiveSnapshot(1, List.of()));
        var keys = new ApplicationKeyService(database, applications, new JdbcApplicationKeyRepository(),
                new AccessTokenService(AccessTokenService.fixedPepper(1, "test-only-pepper")),
                audits, tx, clock, "STANDALONE_SERVER");
        mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(service),
                        new ApplicationKeyController(keys))
                .setControllerAdvice(new AdminErrorHandler()).addFilters(new RequestIdFilter()).build();
        applicationId = service.create(identity("admin", Roles.SYSTEM_ADMIN, List.of()),
                new ApplicationCreateCommand("contract-app", "Contract application", "IT", "owner", "Owner",
                        "TEST", null, "ACTIVE", 1000L, "10.50", "CNY", 60, 10000L,
                        "LIFECYCLE", null, null, List.of())).id();
    }

    @Test
    void subresourcesMatchDetailAndUseSnakeCase() throws Exception {
        JsonNode detail = read(mvc.perform(asOwner(get(base()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        JsonNode list = read(mvc.perform(asOwner(get("/admin/applications"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("items").get(0);
        assertThat(detail.path("version").isTextual()).isTrue();
        assertThat(list.path("version").isTextual()).isTrue();
        assertThat(list.path("version")).isEqualTo(detail.path("version"));
        JsonNode quota = read(mvc.perform(asOwner(get(base() + "/quota")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.amount_limit").value("10.5"))
                .andReturn().getResponse().getContentAsString()).path("data");
        JsonNode models = read(mvc.perform(asOwner(get(base() + "/models")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(quota).isEqualTo(detail.path("quota"));
        assertThat(models).isEqualTo(detail.path("models"));
        assertThat(quota.has("amountLimit")).isFalse();
    }

    /**
     * RV-P20-002：SNAKE_CASE 不会在以数字结尾的字段名中补下划线，原输出 requests24h/success_rate24h
     * 与 BACKEND_PLAN 应用列表契约 requests_24h/success_rate_24h 不一致，必须显式声明并回归断言。
     */
    @Test
    void listRowExposesContractNamesFor24hSummaryAndBudgetStatus() throws Exception {
        JsonNode list = read(mvc.perform(asOwner(get("/admin/applications"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("items").get(0);
        assertThat(list.path("requests_24h").isTextual()).isTrue();
        assertThat(list.path("requests_24h").asText()).isEqualTo("0");
        assertThat(list.has("success_rate_24h")).isTrue();
        assertThat(list.path("success_rate_24h").isNull()).isTrue();
        assertThat(list.has("requests24h")).isFalse();
        assertThat(list.has("success_rate24h")).isFalse();
        assertThat(list.path("budget_status").asText()).isEqualTo("NORMAL");
    }

    @Test
    void fourRolesReadScopedSubresourcesAndOutsidersAreDenied() throws Exception {
        for (String suffix : List.of("/quota", "/models", "/keys", "/members")) {
            for (String role : List.of(Roles.SYSTEM_ADMIN, Roles.OPERATOR,
                    Roles.APPLICATION_OWNER, Roles.AUDITOR)) {
                var context = identity(role.equals(Roles.APPLICATION_OWNER) ? "owner" : "reader",
                        role, role.equals(Roles.AUDITOR) ? List.of("contract-app") : List.of());
                mvc.perform(get(base() + suffix).requestAttr(RequestContext.ATTRIBUTE, context))
                        .andExpect(status().isOk());
            }
            mvc.perform(get(base() + suffix).requestAttr(RequestContext.ATTRIBUTE,
                            identity("outsider", Roles.APPLICATION_OWNER, List.of())))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
            mvc.perform(get(base() + suffix)).andExpect(status().isForbidden());
            mvc.perform(get(base() + suffix).requestAttr(RequestContext.ATTRIBUTE,
                            identity("unscoped-auditor", Roles.AUDITOR, List.of())))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void keysReturnDataOnEveryLifecycleOperationAndNeverReadSecretBack() throws Exception {
        String created = mvc.perform(asOwner(post(base() + "/keys")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"deployment\",\"ip_allowlist\":[\"127.0.0.1\"],\"rpm\":30}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.secret").isString()).andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.data.key_prefix").isString())
                .andReturn().getResponse().getContentAsString();
        JsonNode issued = read(created).path("data");
        String key = issued.path("key_id").asText();
        String secret = issued.path("secret").asText();
        String path = base() + "/keys/" + key;
        String listed = mvc.perform(asOwner(get(base() + "/keys"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].application_id").value(applicationId))
                .andExpect(jsonPath("$.data[0].secret").doesNotExist())
                .andExpect(jsonPath("$.data[0].key_value").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(secret);
        mvc.perform(asOwner(post(path + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"version\":1,\"reason\":\"maintenance\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.entity.status").value("DISABLED"));
        mvc.perform(asOwner(post(path + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"version\":1,\"reason\":\"stale\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONFIG_VERSION_CONFLICT"));
        mvc.perform(asOwner(post(path + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"version\":2,\"reason\":\"resume\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.entity.status").value("ACTIVE"));
        String rotated = mvc.perform(asOwner(post(path + "/rotate")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"reason\":\"rotate\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("4"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(read(rotated).path("data").path("secret").asText()).isNotEqualTo(secret);
        mvc.perform(asOwner(post(path + "/revoke")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4,\"reason\":\"retire\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.entity.status").value("REVOKED"));
        mvc.perform(asOwner(post(path + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"version\":5,\"reason\":\"restore\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void badIdsMissingBodiesAndParserErrorsAre400WithoutInputDisclosure() throws Exception {
        for (String id : List.of("not-a-uuid", "1-1-1-1-1")) {
            for (String suffix : List.of("", "/quota", "/models", "/keys")) {
                mvc.perform(asOwner(get("/admin/applications/" + id + suffix)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("FIELD_VALIDATION_FAILED"));
            }
        }
        for (String body : List.of("", "null", "{}", "{", "{\"name\":\"key\",\"rpm\":\"sensitive-input-marker\"}")) {
            String response = mvc.perform(asOwner(post(base() + "/keys"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.request_id").isString())
                    .andExpect(jsonPath("$.error.retryable").value(false))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("sensitive-input-marker");
        }
        mvc.perform(asOwner(post("/admin/applications")).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(asOwner(post(base() + "/keys/not-a-uuid/rotate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"rotate\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingApplicationBadPaginationAndReadOnlyWrites() throws Exception {
        for (String suffix : List.of("/quota", "/models", "/keys", "/members")) {
            mvc.perform(asOwner(get("/admin/applications/" + UUID.randomUUID() + suffix)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));
        }
        mvc.perform(asOwner(get("/admin/applications").param("page", "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(asOwner(get("/admin/applications").param("keyword", "contract").param("page_size", "1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].code").value("contract-app"));
        for (String role : List.of(Roles.OPERATOR, Roles.AUDITOR)) {
            mvc.perform(post(base() + "/keys").requestAttr(RequestContext.ATTRIBUTE,
                            identity("readonly", role, List.of("contract-app")))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"denied\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(asOwner(get(base() + "/keys"))).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void missingQuotaIsUnavailableInsteadOfFabricatedEmptySuccess() throws Exception {
        new JdbcTemplate(database).update("DELETE FROM application_quota_policy WHERE application_id = ?", applicationId);
        mvc.perform(asOwner(get(base() + "/quota"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CONFIG_DATA_UNAVAILABLE"));
    }

    private String base() { return "/admin/applications/" + applicationId; }
    private static MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request) {
        return request.requestAttr(RequestContext.ATTRIBUTE, identity("owner", Roles.APPLICATION_OWNER, List.of()));
    }
    private static RequestContext identity(String subject, String role, List<String> scope) {
        return new RequestContext(AuthContext.authenticated(subject, subject, Set.of(role), scope),
                "req-contract", "127.0.0.*");
    }
    private static JsonNode read(String json) throws Exception { return ProtocolJson.protocol().readTree(json); }
}