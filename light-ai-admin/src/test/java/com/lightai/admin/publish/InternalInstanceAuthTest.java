package com.lightai.admin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.lightai.client.error.LightAiException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class InternalInstanceAuthTest {
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final InternalInstanceAuth auth = new InternalInstanceAuth(Map.of(first, "first-secret", second, "second-secret"));

    @Test void bindsIdentityFromDeploymentCredentialWithoutTrustingHeaders() {
        assertThat(auth.authenticate(request("first-secret", null))).contains(first);
        assertThat(auth.authenticate(request("second-secret", second.toString()))).contains(second);
    }
    @Test void rejectsForgedHeaderEvenWhenBodyWouldMatchIt() {
        assertThatThrownBy(() -> auth.authenticate(request("first-secret", second.toString())))
                .isInstanceOf(LightAiException.class);
    }
    @Test void rejectsCallerConstructedTokenAndSharedToken() {
        assertThatThrownBy(() -> auth.authenticate(request("first-secret:" + second, second.toString())))
                .isInstanceOf(LightAiException.class);
        assertThatThrownBy(() -> new InternalInstanceAuth("shared").authenticate(request("shared", first.toString())))
                .isInstanceOf(LightAiException.class);
    }
    @Test void rejectsMissingUnknownAndMalformedCredentials() {
        for (String value : new String[]{null, "wrong", ""}) {
            assertThatThrownBy(() -> auth.authenticate(request(value, null))).isInstanceOf(LightAiException.class);
        }
        assertThatThrownBy(() -> auth.authenticate(request("first-secret", "not-a-uuid")))
                .isInstanceOf(LightAiException.class);
    }
    @Test void rejectsUnconfiguredAndReusedCredentials() {
        assertThatThrownBy(() -> new InternalInstanceAuth(Map.of()).authenticate(request("first-secret", null)))
                .isInstanceOf(LightAiException.class);
        assertThatThrownBy(() -> new InternalInstanceAuth(Map.of(first, "same", second, "same")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresAuthenticatedIdentityToMatchBody() {
        var request = request("first-secret", null);
        assertThatThrownBy(() -> InternalInstanceAuth.requireIdentity(request, first.toString()))
                .isInstanceOf(LightAiException.class);
        request.setAttribute(InternalInstanceAuth.ATTRIBUTE, auth.authenticate(request).orElseThrow());
        assertThat(InternalInstanceAuth.requireIdentity(request, first.toString())).isEqualTo(first);
        assertThatThrownBy(() -> InternalInstanceAuth.requireIdentity(request, second.toString()))
                .isInstanceOf(LightAiException.class);
    }
    private MockHttpServletRequest request(String token, String id) {
        var request = new MockHttpServletRequest();
        if (token != null) request.addHeader(InternalInstanceAuth.TOKEN_HEADER, token);
        if (id != null) request.addHeader(InternalInstanceAuth.INSTANCE_ID_HEADER, id);
        return request;
    }
}
