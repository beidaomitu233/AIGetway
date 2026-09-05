package com.lightai.admin.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import org.junit.jupiter.api.Test;

/**
 * Provider 目标地址安全策略验收（BE-008）：
 * 禁止认证头之外的 SSRF 面——内部网段、userinfo、非 http(s) 协议。
 */
class TargetUrlPolicyTest {

    private final TargetUrlPolicy policy = new TargetUrlPolicy(false);

    @Test
    void publicHttpAndHttpsAccepted() {
        assertThat(policy.validate("https://api.openai.com/v1", "base_url"))
                .isEqualTo("https://api.openai.com/v1");
        assertThatCode(() -> policy.validate("http://api.deepseek.com", "base_url"))
                .doesNotThrowAnyException();
    }

    @Test
    void nonHttpSchemesRejected() {
        for (String url : new String[] {"ftp://example.com", "file:///etc/passwd", "example.com"}) {
            assertThatThrownBy(() -> policy.validate(url, "base_url"))
                    .isInstanceOf(LightAiException.class)
                    .extracting(e -> ((LightAiException) e).code())
                    .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
        }
    }

    @Test
    void userinfoAndFragmentRejected() {
        assertThatThrownBy(() -> policy.validate("https://user:pass@example.com", "base_url"))
                .isInstanceOf(LightAiException.class);
        assertThatThrownBy(() -> policy.validate("https://example.com/path#fragment", "base_url"))
                .isInstanceOf(LightAiException.class);
    }

    @Test
    void internalNetworksRejectedByDefault() {
        for (String host : new String[] {"https://localhost:8080", "http://127.0.0.1:11434",
                "http://10.1.2.3", "http://172.16.0.9", "http://172.31.255.1",
                "http://192.168.1.1", "http://169.254.10.10", "https://metadata.internal"}) {
            assertThatThrownBy(() -> policy.validate(host, "base_url"))
                    .as("internal target should be rejected: " + host)
                    .isInstanceOf(LightAiException.class);
        }
    }

    @Test
    void internalNetworksAllowedWithExplicitDeploymentConsent() {
        TargetUrlPolicy permissive = new TargetUrlPolicy(true);
        assertThatCode(() -> permissive.validate("http://192.168.1.50:8000/v1", "base_url"))
                .doesNotThrowAnyException();
    }

    @Test
    void fieldIssuesCarryFieldName() {
        LightAiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> policy.validate("http://127.0.0.1", "proxy_url"), LightAiException.class);
        assertThat(e.issues().stream().map(FieldIssue::field).toList()).containsExactly("proxy_url");
    }
}
