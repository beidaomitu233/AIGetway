package com.lightai.admin.web;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** WebFlux 会话 CSRF Token；语义与 Servlet CsrfTokenService 一致。 */
public final class ReactiveCsrfTokenService {
    private static final String SESSION_ATTRIBUTE = "com.lightai.admin.csrfToken";
    private static final SecureRandom RANDOM = new SecureRandom();

    public Mono<String> currentToken(ServerWebExchange exchange) {
        return exchange.getSession().map(session -> {
            Object current = session.getAttribute(SESSION_ATTRIBUTE);
            if (current instanceof String value && !value.isBlank()) {
                return value;
            }
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.getAttributes().put(SESSION_ATTRIBUTE, generated);
            return generated;
        });
    }

    public Mono<Boolean> matches(ServerWebExchange exchange, String provided) {
        if (provided == null || provided.isBlank()) {
            return Mono.just(false);
        }
        return exchange.getSession().map(session -> {
            Object expected = session.getAttribute(SESSION_ATTRIBUTE);
            return expected instanceof String value && MessageDigest.isEqual(
                    value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    provided.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
    }
}
