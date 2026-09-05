package com.lightai.admin.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.lightai.admin.AdminProperties;
import com.lightai.admin.storage.ManagementStateReader;
import com.lightai.admin.web.AdminAuthInterceptor;
import com.lightai.admin.web.CsrfTokenFilter;
import com.lightai.admin.web.CsrfTokenService;
import com.lightai.admin.web.RequestIdFilter;
import com.lightai.client.bootstrap.AdapterDeclaration;
import com.lightai.client.json.ProtocolJson;
import com.lightai.spi.adapter.AdapterMetadataSource;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthContextProviders;
import com.lightai.spi.auth.AuthRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BE-002 接口测试：GET /admin/bootstrap 四角色矩阵、未认证拒绝、
 * CSRF 写检查与 request_id 贯穿。身份由测试 AuthContextProvider 注入。
 */
class BootstrapWebTest {

    private static final String ROLE_HEADER = "X-Test-Roles";
    private static final String USER_HEADER = "X-Test-User";

    private MockMvc mockMvc;
    private CsrfTokenService csrfTokenService;

    @BeforeEach
    void setUp() {
        csrfTokenService = new CsrfTokenService();
        AdminProperties properties = new AdminProperties();
        properties.setRuntimeMode("STANDALONE_SERVER");
        properties.setUiBasePath("/light-ai");
        properties.setAdminApiBasePath("/admin");

        BootstrapService service = new BootstrapService(properties,
                () -> ManagementStateReader.ManagementState.defaults("Asia/Shanghai"),
                staticAdapterSource());
        BootstrapController controller = new BootstrapController(service, csrfTokenService, true);

        AuthContextProvider identityProvider = testIdentityProvider();
        mockMvc = MockMvcBuilders.standaloneSetup(controller, new DummyWriteController())
                .setControllerAdvice(new com.lightai.admin.web.AdminErrorHandler())
                .addFilters(new RequestIdFilter(), new CsrfTokenFilter(csrfTokenService))
                .addInterceptors(new AdminAuthInterceptor(identityProvider))
                .build();
    }

    @Test
    void systemAdminReceivesFullPermissionMatrix() throws Exception {
        JsonNode data = bootstrapFor("SYSTEM_ADMIN");

        assertThat(data.get("user").get("id").asText()).isEqualTo("user-admin");
        assertThat(data.get("roles").toString()).contains("SYSTEM_ADMIN");
        assertThat(data.get("permissions").size()).isEqualTo(35);
        assertThat(data.get("permissions").toString())
                .contains("provider.manage").contains("publish.manage")
                .contains("credential.view").contains("developer.test");
        assertThat(data.get("runtime_mode").asText()).isEqualTo("STANDALONE_SERVER");
        assertThat(data.get("admin_api_base_path").asText()).isEqualTo("/admin");
        assertThat(data.get("current_snapshot_no").asLong()).isZero();
        assertThat(data.get("draft_revision").asLong()).isZero();
        assertThat(data.get("adapters").toString()).contains("OPENAI");
    }

    @Test
    void operatorCanOperateCircuitButNotManageConfiguration() throws Exception {
        JsonNode data = bootstrapFor("OPERATOR");

        assertThat(data.get("permissions").toString())
                .contains("circuit.operate").contains("audit.export")
                .contains("trace.diagnostics").contains("access.view");
        assertThat(data.get("permissions").toString())
                .doesNotContain("provider.manage").doesNotContain("draft.revert")
                .doesNotContain("publish.manage").doesNotContain("runtimeconfig.manage");
    }

    @Test
    void developerHasNoCredentialOrAuditVisibility() throws Exception {
        JsonNode data = bootstrapFor("DEVELOPER");

        assertThat(data.get("permissions").toString())
                .contains("developer.test").contains("trace.view");
        assertThat(data.get("permissions").toString())
                .doesNotContain("credential.view").doesNotContain("audit.view")
                .doesNotContain("access.view").doesNotContain("trace.export");
    }

    @Test
    void viewerHasReadOnlyPermissions() throws Exception {
        JsonNode data = bootstrapFor("VIEWER");

        assertThat(data.get("permissions").toString())
                .contains("overview.view").contains("developer.view");
        assertThat(data.get("permissions").toString())
                .doesNotContain("developer.test").doesNotContain("circuit.operate")
                .doesNotContain("audit.view").doesNotContain("access.view");
    }

    @Test
    void unknownRoleGetsNoPermissions() throws Exception {
        JsonNode data = bootstrapFor("MYSTERY_ROLE");

        assertThat(data.get("permissions").size()).isZero();
    }

    @Test
    void anonymousIdentityIsDeniedWithEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/bootstrap"))
                .andExpect(status().isForbidden())
                .andReturn();

        JsonNode body = ProtocolJson.protocol().readTree(result.getResponse().getContentAsString());
        assertThat(body.get("error").get("code").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(body.get("error").get("retryable").asBoolean()).isFalse();
        assertThat(body.get("error").get("request_id").asText()).isNotBlank();
        // 未认证不泄露任何身份与权限信息
        assertThat(body.has("data")).isFalse();
    }

    @Test
    void requestIdHeaderIsEchoedOnResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/bootstrap")
                        .header(ROLE_HEADER, "SYSTEM_ADMIN")
                        .header(USER_HEADER, "user-admin")
                        .header("X-Request-Id", "req-42"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("req-42");
    }

    @Test
    void csrfProtectedWriteRequiresSessionTokenFromBootstrap() throws Exception {
        org.springframework.mock.web.MockHttpSession session =
                new org.springframework.mock.web.MockHttpSession();

        MvcResult bootstrapResult = mockMvc.perform(get("/admin/bootstrap")
                        .header(ROLE_HEADER, "SYSTEM_ADMIN")
                        .header(USER_HEADER, "user-admin")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = ProtocolJson.protocol()
                .readTree(bootstrapResult.getResponse().getContentAsString())
                .get("data");
        String token = data.get("csrf_token").asText();
        assertThat(token).isNotBlank();

        // 无 Token 的写请求拒绝（CSRF 检查先于身份）
        mockMvc.perform(post("/admin/dummy").session(session)
                        .header(ROLE_HEADER, "SYSTEM_ADMIN").header(USER_HEADER, "user-admin"))
                .andExpect(status().isForbidden());

        // 携带会话 Token 的写请求通过
        mockMvc.perform(post("/admin/dummy").session(session)
                        .header(ROLE_HEADER, "SYSTEM_ADMIN").header(USER_HEADER, "user-admin")
                        .header("X-CSRF-Token", token))
                .andExpect(status().isOk());
    }

    private JsonNode bootstrapFor(String role) throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/bootstrap")
                        .header(ROLE_HEADER, role)
                        .header(USER_HEADER, "user-admin"))
                .andExpect(status().isOk())
                .andReturn();
        return ProtocolJson.protocol().readTree(result.getResponse().getContentAsString())
                .get("data");
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

    private static AdapterMetadataSource staticAdapterSource() {
        return () -> List.of(new AdapterDeclaration("OPENAI", "1.0.0-test",
                "https://api.openai.com/v1/", List.of("O200K"),
                List.of("CHAT", "STREAM"), List.of()));
    }

    @RestController
    static class DummyWriteController {
        @GetMapping("/admin/dummy")
        String read() {
            return "read";
        }

        @PostMapping("/admin/dummy")
        String write() {
            return "written";
        }
    }
}
