package com.lightai.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 错误映射与安全响应（BE-001/BE-002）：错误码→HTTP、request_id 注入、
 * 未分类异常不泄漏内部细节。
 */
class AdminErrorHandlerTest {

    private final AdminErrorHandler handler = new AdminErrorHandler();

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/admin/providers/1");
        request.addHeader("X-Request-Id", "req-err-1");
        request.setAttribute(AdminAuthInterceptor.RequestStart.ATTRIBUTE, System.nanoTime() - 1_000_000);
        return request;
    }

    @Test
    void lightAiExceptionMapsToPlanHttpStatusAndCarriesCurrentVersion() {
        LightAiException exception = new LightAiException(
                ErrorCode.CONFIG_VERSION_CONFLICT, "对象版本已变化", null, "req-err-1",
                null, 7L, null);

        var response = handler.handleLightAiException(exception, request());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("\"code\":\"CONFIG_VERSION_CONFLICT\"");
        assertThat(response.getBody()).contains("\"current_version\":7");
        assertThat(response.getBody()).contains("\"request_id\":\"req-err-1\"");
        assertThat(response.getBody()).contains("\"error\":");
    }

    @Test
    void missingRequestIdIsFilledFromRequestHeader() {
        LightAiException exception = new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "字段不合法");
        var response = handler.handleLightAiException(exception, request());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"request_id\":\"req-err-1\"");
        assertThat(response.getBody()).contains("\"type\":\"invalid_request_error\"");
    }

    @Test
    void fieldValidationIssuesAreCarriedIntoEnvelope() {
        LightAiException exception = new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "查询参数不合法",
                java.util.List.of(new com.lightai.client.error.FieldIssue("sort", "NOT_ALLOWED", "排序列不在允许范围")));
        var response = handler.handleLightAiException(exception, request());
        assertThat(response.getBody()).contains("\"errors\":[{\"field\":\"sort\",\"code\":\"NOT_ALLOWED\"");
    }

    @Test
    void unexpectedExceptionIsGenericInternalError() {
        var response = handler.handleUnexpected(new IllegalStateException("jdbc password=secret123"), request());
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).contains("\"code\":\"INTERNAL_ERROR\"");
        // 内部细节不得回传客户端
        assertThat(response.getBody()).doesNotContain("secret123");
        assertThat(response.getBody()).doesNotContain("jdbc");
    }

    @Test
    void sdkLocalErrorCodesNeverProduceFabricatedStatus() {
        LightAiException exception = new LightAiException(ErrorCode.CLIENT_CLOSED, "客户端已关闭");
        var response = handler.handleLightAiException(exception, request());
        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void interceptorDeniesAnonymousWithEnvelopeAndContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/bootstrap");
        request.addHeader("X-Request-Id", "req-anon");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthContextProvider denyAll = authRequest -> AuthContext.anonymous();

        boolean proceed = new AdminAuthInterceptor(denyAll).preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACCESS_DENIED");
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        assertThat(context.requestId()).isEqualTo("req-anon");
        assertThat(context.sourceIpMasked()).isEqualTo("127.0.0.*");
    }

    @Test
    void interceptorBuildsImmutableContextForAuthenticatedRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/bootstrap");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthContextProvider provider = authRequest -> AuthContext.authenticated(
                "u-1", "n", java.util.Set.of("VIEWER"), java.util.List.of("console"));

        boolean proceed = new AdminAuthInterceptor(provider).preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        assertThat(context.authContext().roles()).containsExactly("VIEWER");
    }
}
