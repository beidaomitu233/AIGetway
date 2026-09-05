package com.lightai.spi.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthContextTest {

    @Test
    void anonymousContextIsUnauthenticatedWithoutRoles() {
        AuthContext context = AuthContext.anonymous();
        assertThat(context.authenticated()).isFalse();
        assertThat(context.roles()).isEmpty();
        assertThat(context.applicationScope()).isEmpty();
    }

    @Test
    void authenticatedContextIsImmutable() {
        AuthContext context = AuthContext.authenticated("u-1", "管理员", Set.of("SYSTEM_ADMIN"),
                List.of("console"));
        assertThat(context.roles()).containsExactly("SYSTEM_ADMIN");
    }

    @Test
    void denyAllProviderRejectsEveryRequest() {
        AuthContext context = AuthContextProviders.denyAll()
                .resolve(new AuthRequest("GET", "/admin/bootstrap", Map.of(), "127.0.0.1"));
        assertThat(context.authenticated()).isFalse();
    }

    @Test
    void nullCollectionsCollapseToEmpty() {
        AuthContext context = new AuthContext(true, "u-1", "n", null, null);
        assertThat(context.roles()).isEmpty();
        assertThat(context.applicationScope()).isEmpty();
    }
}
