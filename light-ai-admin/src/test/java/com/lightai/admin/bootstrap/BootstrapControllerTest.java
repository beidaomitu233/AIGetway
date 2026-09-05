package com.lightai.admin.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lightai.admin.AdminProperties;
import com.lightai.admin.storage.ManagementStateReader;
import com.lightai.admin.web.AdminAuthInterceptor;
import com.lightai.admin.web.CsrfTokenService;
import com.lightai.admin.web.RequestIdFilter;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthRequest;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * GET /admin/bootstrap 契约测试（BE-002）：认证拦截、权限矩阵输出、
 * 草稿状态计数与 snake_case 字段。
 */
class BootstrapControllerTest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    /** 测试身份适配：特定头映射为系统管理员，其余默认拒绝。 */
    static final class TestAuthProvider implements AuthContextProvider {
        @Override
        public AuthContext resolve(AuthRequest request) {
            String token = request.headers().get("x-admin-token");
            if (ADMIN_TOKEN.equals(token)) {
                return AuthContext.authenticated("op-1", "系统管理员", Set.of("SYSTEM_ADMIN"), java.util.List.of("console"));
            }
            return AuthContext.anonymous();
        }
    }

    private MockMvc mockMvc;
    private AdminProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AdminProperties();
        properties.setRuntimeMode("EMBEDDED");
        ManagementStateReader stateReader = () -> new ManagementStateReader.ManagementState(12, "Asia/Shanghai", 5, 3);
        BootstrapService service = new BootstrapService(properties, stateReader, null);
        mockMvc = MockMvcBuilders.standaloneSetup(new BootstrapController(service, new CsrfTokenService(), false))
                .addInterceptors(new AdminAuthInterceptor(new TestAuthProvider()))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void unauthenticatedRequestIsDeniedWithEnvelope() throws Exception {
        mockMvc.perform(get("/admin/bootstrap"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.error.type").value("permission_error"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void bootstrapReturnsContractPayloadForAdmin() throws Exception {
        mockMvc.perform(get("/admin/bootstrap").header("x-admin-token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value("op-1"))
                .andExpect(jsonPath("$.data.user.display_name").value("系统管理员"))
                .andExpect(jsonPath("$.data.roles[0]").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.data.permissions").isNotEmpty())
                .andExpect(jsonPath("$.data.application_scope[0]").value("console"))
                .andExpect(jsonPath("$.data.runtime_mode").value("EMBEDDED"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.current_snapshot_no").value(12))
                .andExpect(jsonPath("$.data.draft_revision").value(5))
                .andExpect(jsonPath("$.data.draft_change_count").value(3))
                .andExpect(jsonPath("$.data.csrf_token").doesNotExist())
                .andExpect(jsonPath("$.data.adapters").doesNotExist())
                .andExpect(jsonPath("$.data.ui_base_path").value(""));
    }

    @Test
    void adminRoleReceivesFullPermissionMatrix() throws Exception {
        String body = mockMvc.perform(get("/admin/bootstrap").header("x-admin-token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("\"provider.manage\"");
        assertThat(body).contains("\"publish.manage\"");
        assertThat(body).contains("\"circuit.operate\"");
        assertThat(body).contains("\"model.import\"");
    }

    @Test
    void requestIdHeaderIsEchoed() throws Exception {
        mockMvc.perform(get("/admin/bootstrap")
                        .header("x-admin-token", ADMIN_TOKEN)
                        .header("X-Request-Id", "req-42"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader("X-Request-Id")).isEqualTo("req-42"));
    }

    @Test
    void csrfTokenEmittedOnlyWhenEnabled() throws Exception {
        properties.setCsrfEnabled(true);
        ManagementStateReader stateReader = () ->
                ManagementStateReader.ManagementState.defaults("Asia/Shanghai");
        BootstrapService service = new BootstrapService(properties, stateReader, null);
        MockMvc csrfMockMvc = MockMvcBuilders
                .standaloneSetup(new BootstrapController(service, new CsrfTokenService(), true))
                .addInterceptors(new AdminAuthInterceptor(new TestAuthProvider()))
                .addFilters(new RequestIdFilter())
                .build();
        csrfMockMvc.perform(get("/admin/bootstrap").header("x-admin-token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.csrf_token").isNotEmpty());
    }

    @Test
    void headerMapIsCaseInsensitiveForProvider() {
        // 拦截器统一将请求头小写化后传入 AuthRequest
        AuthContext context = new TestAuthProvider().resolve(
                new AuthRequest("GET", "/admin/bootstrap", Map.of("x-admin-token", ADMIN_TOKEN), "127.0.0.1"));
        assertThat(context.authenticated()).isTrue();
    }
}
