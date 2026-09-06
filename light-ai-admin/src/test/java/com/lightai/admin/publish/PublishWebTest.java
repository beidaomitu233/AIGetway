package com.lightai.admin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.lightai.admin.web.AdminAuthInterceptor;
import com.lightai.admin.web.AdminErrorHandler;
import com.lightai.admin.web.RequestIdFilter;
import com.lightai.client.json.ProtocolJson;
import com.lightai.client.publish.ConfigPublishCommand;
import com.lightai.client.publish.ConfigValidateCommand;
import com.lightai.client.publish.RevertAllDraftCommand;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthContextProviders;
import com.lightai.admin.AdminProperties;
import com.lightai.spi.auth.AuthRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * BE-037~042 接口测试：四角色权限、统一错误信封、内部实例认证与 request_id 贯穿。
 * 身份由测试 AuthContextProvider 注入（X-Test-Roles 头）。
 */
class PublishWebTest {

    private static final String ROLE_HEADER = "X-Test-Roles";
    private static final String USER_HEADER = "X-Test-User";

    private MockMvc mockMvc;
    private PublishTestSupport.FakeValidationRepository validations;
    private PublishTestSupport.FakeSnapshotContentRepository content;
    private ConfigPublishService publishService;
    private DraftRevertService revertService;

    @BeforeEach
    void setUp() {
        PublishTestSupport.RecordingConnection recording = new PublishTestSupport.RecordingConnection();
        var draftState = new PublishTestSupport.FakeDraftStateRepository(recording.calls);
        var changes = new PublishTestSupport.FakeDraftChangeQueryRepository(recording.calls);
        var snapshots = new PublishTestSupport.FakeSnapshotRepository(recording.calls);
        content = new PublishTestSupport.FakeSnapshotContentRepository(recording.calls);
        content.content = ConfigValidationServiceTest.validContent();
        validations = new PublishTestSupport.FakeValidationRepository(recording.calls);
        var publishes = new PublishTestSupport.FakePublishRecordRepository(recording.calls);
        var instanceResults = new PublishTestSupport.FakeInstanceResultRepository(recording.calls);
        var instances = new PublishTestSupport.FakeRuntimeInstanceRepository(recording.calls);
        instances.online(UUID.randomUUID());
        var audits = new PublishTestSupport.RecordingAuditRepository(recording.calls);
        var dependencies = new PublishTestSupport.FakeDependencyRepository(recording.calls);
        var transactionManager = new DataSourceTransactionManager(recording.dataSource());
        transactionManager.afterPropertiesSet();
        var auditService = new com.lightai.admin.audit.AuditService(audits, recording.dataSource(),
                transactionManager, new PublishTestSupport.FailureCollector());

        var queryService = new DraftStateQueryService(recording.dataSource(), draftState,
                changes, dependencies);
        revertService = new DraftRevertService(recording.dataSource(), transactionManager,
                draftState, draftState, changes, snapshots, content, dependencies, auditService,
                "STANDALONE_SERVER");
        var validationService = new ConfigValidationService(recording.dataSource(), transactionManager,
                java.time.Clock.systemUTC(), draftState, changes, snapshots, content, validations,
                instances, new com.lightai.admin.provider.ProviderTypeRegistry(
                        (com.lightai.spi.adapter.AdapterMetadataSource) List::of),
                new ConfigValidationServiceTest.StubCheckRecordRepository(), auditService,
                "Asia/Shanghai", "STANDALONE_SERVER");
        AdminProperties webProperties = new AdminProperties();
        webProperties.setRuntimeMode("STANDALONE_SERVER");
        publishService = new ConfigPublishService(recording.dataSource(), transactionManager,
                java.time.Clock.systemUTC(), draftState, draftState, changes, snapshots, content,
                validations, publishes, instanceResults, instances, auditService, webProperties);

        var internalAuth = new InternalInstanceAuth("deploy-secret");
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ConfigDraftController(queryService, revertService),
                        new ConfigPublishController(validationService, publishService),
                        new InternalInstanceController(publishService, internalAuth))
                .setControllerAdvice(new AdminErrorHandler())
                .addFilters(new RequestIdFilter())
                .addInterceptors(
                        new org.springframework.web.servlet.handler.MappedInterceptor(
                                new String[]{"/admin/**"}, new AdminAuthInterceptor(testIdentityProvider())),
                        new org.springframework.web.servlet.handler.MappedInterceptor(
                                new String[]{"/internal/**"}, new InternalAuthInterceptor(internalAuth)))
                .build();
    }

    @Test
    void draftStateIsReadableByEveryViewingRole() throws Exception {
        for (String role : List.of("SYSTEM_ADMIN", "OPERATOR", "DEVELOPER", "VIEWER")) {
            MvcResult result = mockMvc.perform(get("/admin/config/draft-state")
                            .header(ROLE_HEADER, role).header(USER_HEADER, "user-" + role))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode data = ProtocolJson.protocol()
                    .readTree(result.getResponse().getContentAsString()).get("data");
            assertThat(data.get("status").asText()).isEqualTo("EDITABLE");
            assertThat(data.get("draft_revision").asLong()).isEqualTo(5);
            assertThat(data.has("first_modified_at")).isTrue();
        }
    }

    @Test
    void revertRequiresDraftRevertPermission() throws Exception {
        String body = "{\"version\":1,\"draft_revision\":5,\"reason\":\"撤销\"}";
        // 系统管理员可撤销；对象不存在时返回 OBJECT_NOT_FOUND（权限已通过）
        MvcResult adminResult = mockMvc.perform(post(
                        "/admin/config/draft-changes/provider/" + UUID.randomUUID() + "/revert")
                        .header(ROLE_HEADER, "SYSTEM_ADMIN").header(USER_HEADER, "admin")
                        .contentType("application/json").content(body))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(errorCode(adminResult)).isEqualTo("OBJECT_NOT_FOUND");

        // 运维/开发/只读无 draft.revert
        for (String role : List.of("OPERATOR", "DEVELOPER", "VIEWER")) {
            MvcResult result = mockMvc.perform(post(
                            "/admin/config/draft-changes/provider/" + UUID.randomUUID() + "/revert")
                            .header(ROLE_HEADER, role).header(USER_HEADER, "user-" + role)
                            .contentType("application/json").content(body))
                    .andExpect(status().isForbidden())
                    .andReturn();
            assertThat(errorCode(result)).isEqualTo("ACCESS_DENIED");
        }
    }

    @Test
    void validateAndPublishRequirePublishManage() throws Exception {
        // 开发/只读连 draft-changes 之外的发布面都不可进入
        for (String role : List.of("OPERATOR", "DEVELOPER", "VIEWER")) {
            MvcResult result = mockMvc.perform(post("/admin/config/validate")
                            .header(ROLE_HEADER, role).header(USER_HEADER, "user-" + role)
                            .contentType("application/json").content("{\"draft_revision\":5}"))
                    .andExpect(status().isForbidden())
                    .andReturn();
            assertThat(errorCode(result)).isEqualTo("ACCESS_DENIED");
        }

        // 系统管理员：权限通过后进入业务校验；夹具草稿有效时校验通过
        MvcResult adminResult = mockMvc.perform(post("/admin/config/validate")
                        .header(ROLE_HEADER, "SYSTEM_ADMIN").header(USER_HEADER, "admin")
                        .contentType("application/json").content("{\"draft_revision\":5}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = ProtocolJson.protocol()
                .readTree(adminResult.getResponse().getContentAsString()).get("data");
        assertThat(data.get("status").asText()).isEqualTo("PASSED");
        assertThat(data.get("expires_at").asText()).isNotBlank();
    }

    @Test
    void unknownBodyFieldIsRejectedStrictly() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/config/validate")
                        .header(ROLE_HEADER, "SYSTEM_ADMIN").header(USER_HEADER, "admin")
                        .contentType("application/json")
                        .content("{\"draft_revision\":5,\"unknown_field\":1}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(errorCode(result)).isEqualTo("FIELD_VALIDATION_FAILED");
    }

    @Test
    void internalEndpointsRejectRequestsWithoutInstanceToken() throws Exception {
        MvcResult missing = mockMvc.perform(post("/internal/runtime-instances/heartbeat")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode error = ProtocolJson.protocol()
                .readTree(missing.getResponse().getContentAsString()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("INSTANCE_AUTH_FAILED");
        assertThat(error.get("retryable").asBoolean()).isFalse();
        assertThat(error.get("request_id").asText()).isNotBlank();

        MvcResult wrong = mockMvc.perform(post("/internal/runtime-instances/heartbeat")
                        .header("X-Light-AI-Instance-Token", "wrong")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(errorCode(wrong)).isEqualTo("INSTANCE_AUTH_FAILED");
    }

    @Test
    void internalHeartbeatWithTokenUpsertsInstanceAndReturnsCommands() throws Exception {
        MvcResult result = mockMvc.perform(post("/internal/runtime-instances/heartbeat")
                        .header("X-Light-AI-Instance-Token", "deploy-secret")
                        .contentType("application/json")
                        .content("""
                                {"instance_id":"%s","runtime_mode":"STANDALONE_SERVER",
                                 "runtime_version":"1.0.0-test","application":"app",
                                 "supported_schema_versions":["1"],"loaded_adapter_types":["OPENAI"],
                                 "active_snapshot_no":0,"accepting_requests":true}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = ProtocolJson.protocol()
                .readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("server_time")).isNotNull();
        assertThat(data.get("active_snapshot_no").asLong()).isZero();
        assertThat(data.has("prepare_command")).isTrue();
        assertThat(data.has("activation_command")).isTrue();
    }

    @Test
    void requestIdPropagatesToErrorEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/config/publish-records/" + UUID.randomUUID())
                        .header(ROLE_HEADER, "SYSTEM_ADMIN").header(USER_HEADER, "admin")
                        .header("X-Request-Id", "req-pub-1"))
                .andExpect(status().isNotFound())
                .andReturn();
        JsonNode error = ProtocolJson.protocol()
                .readTree(result.getResponse().getContentAsString()).get("error");
        assertThat(error.get("request_id").asText()).isEqualTo("req-pub-1");
        assertThat(error.get("code").asText()).isEqualTo("OBJECT_NOT_FOUND");
        assertThat(error.get("type").asText()).isNotBlank();
    }

    private static String errorCode(MvcResult result) throws Exception {
        return ProtocolJson.protocol()
                .readTree(result.getResponse().getContentAsString())
                .get("error").get("code").asText();
    }

    private static AuthContextProvider testIdentityProvider() {
        return (AuthRequest request) -> {
            String user = request.headers().get(USER_HEADER.toLowerCase());
            String roles = request.headers().get(ROLE_HEADER.toLowerCase());
            if (user == null || roles == null) {
                return AuthContextProviders.denyAll().resolve(request);
            }
            return AuthContext.authenticated(user, user,
                    java.util.Set.of(roles.split(",")), List.of());
        };
    }
}
