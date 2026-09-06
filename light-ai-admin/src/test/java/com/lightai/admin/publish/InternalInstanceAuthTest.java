package com.lightai.admin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * BE-041 内部实例认证单元测试：默认拒绝、常量时间口令比较、身份一致性。
 * 部署共享口令方式为 C-001 相关补充假设，mTLS 身份绑定由部署侧提供后替换。
 */
class InternalInstanceAuthTest {

    @Test
    void unconfiguredTokenDeniesAllRequests() {
        InternalInstanceAuth auth = new InternalInstanceAuth(null);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> auth.authenticate(request))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.INSTANCE_AUTH_FAILED);
    }

    @Test
    void wrongOrMissingTokenIsRejected() {
        InternalInstanceAuth auth = new InternalInstanceAuth("deploy-secret");
        MockHttpServletRequest missing = new MockHttpServletRequest();
        assertThatThrownBy(() -> auth.authenticate(missing))
                .isInstanceOf(LightAiException.class);

        MockHttpServletRequest wrong = new MockHttpServletRequest();
        wrong.addHeader("X-Light-AI-Instance-Token", "guess");
        assertThatThrownBy(() -> auth.authenticate(wrong))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.INSTANCE_AUTH_FAILED);
    }

    @Test
    void correctTokenAuthenticatesWithoutBoundIdentity() {
        InternalInstanceAuth auth = new InternalInstanceAuth("deploy-secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Light-AI-Instance-Token", "deploy-secret");

        Optional<UUID> identity = auth.authenticate(request);
        assertThat(identity).isEmpty();
    }

    @Test
    void requireIdentityRejectsMismatchAndInvalidIds() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID bound = UUID.randomUUID();
        request.setAttribute(InternalInstanceAuth.ATTRIBUTE, bound);

        assertThat(InternalInstanceAuth.requireIdentity(request, bound.toString())).isEqualTo(bound);
        assertThatThrownBy(() -> InternalInstanceAuth.requireIdentity(request, UUID.randomUUID().toString()))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.INSTANCE_AUTH_FAILED);

        MockHttpServletRequest unbound = new MockHttpServletRequest();
        assertThatThrownBy(() -> InternalInstanceAuth.requireIdentity(unbound, "not-a-uuid"))
                .isInstanceOf(LightAiException.class);
    }
}
