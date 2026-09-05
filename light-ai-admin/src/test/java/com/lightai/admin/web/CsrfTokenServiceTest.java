package com.lightai.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CsrfTokenServiceTest {

    private final CsrfTokenService service = new CsrfTokenService();

    @Test
    void tokenIsStablePerSessionAndOpaque() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String first = service.currentToken(request);
        String second = service.currentToken(request);
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotBlank().hasSize(43); // 32字节 base64url 无填充
        assertThat(first).doesNotContain("=");
    }

    @Test
    void writeWithoutTokenIsRejectedAndSafeMethodPasses() throws Exception {
        CsrfTokenFilter filter = new CsrfTokenFilter(service);
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/admin/providers");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};
        filter.doFilter(post, response, (req, res) -> invoked[0] = true);
        assertThat(invoked[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/admin/providers");
        MockHttpServletResponse getResponse = new MockHttpServletResponse();
        filter.doFilter(get, getResponse, (req, res) -> invoked[0] = true);
        assertThat(invoked[0]).isTrue();
    }

    @Test
    void writeWithSessionTokenPasses() throws Exception {
        MockHttpServletRequest bootstrap = new MockHttpServletRequest();
        String token = service.currentToken(bootstrap);
        HttpSession session = bootstrap.getSession();

        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/admin/providers");
        post.setSession(session);
        post.addHeader(CsrfTokenService.HEADER, token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] invoked = {false};
        new CsrfTokenFilter(service).doFilter(post, response, (req, res) -> invoked[0] = true);
        assertThat(invoked[0]).isTrue();
    }

    @Test
    void mismatchedTokenIsRejected() {
        MockHttpServletRequest bootstrap = new MockHttpServletRequest();
        service.currentToken(bootstrap);
        assertThat(service.matches(bootstrap.getSession(), "wrong-token")).isFalse();
        assertThat(service.matches(null, "any")).isFalse();
        assertThat(service.matches(bootstrap.getSession(), null)).isFalse();
    }
}
