package com.lightai.admin.web;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.UnifiedError;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** WebFlux 管理入口的 request_id、身份与 CSRF 统一过滤链。 */
public final class ReactiveAdminWebFilter implements WebFilter, Ordered {
    private static final Set<String> PROTECTED_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final AuthContextProvider authContextProvider;
    private final ReactiveCsrfTokenService csrfTokenService;
    private final boolean csrfEnabled;

    public ReactiveAdminWebFilter(AuthContextProvider authContextProvider,
                                  ReactiveCsrfTokenService csrfTokenService,
                                  boolean csrfEnabled) {
        this.authContextProvider = authContextProvider;
        this.csrfTokenService = csrfTokenService;
        this.csrfEnabled = csrfEnabled;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!(path.equals("/admin") || path.startsWith("/admin/"))) {
            return chain.filter(exchange);
        }

        String requestId = requestId(exchange);
        String sourceIp = sourceIp(exchange);
        exchange.getAttributes().put(RequestIdFilter.ATTRIBUTE, requestId);
        exchange.getAttributes().put(AdminAuthInterceptor.RequestStart.ATTRIBUTE, System.nanoTime());
        exchange.getResponse().getHeaders().set(RequestIdFilter.HEADER, requestId);

        AuthContext authContext = authContextProvider.resolve(new AuthRequest(
                exchange.getRequest().getMethod().name(), path, headers(exchange), sourceIp));
        RequestContext context = new RequestContext(
                authContext, requestId, MaskedSourceIp.mask(sourceIp));
        exchange.getAttributes().put(RequestContext.ATTRIBUTE, context);
        if (!authContext.authenticated()) {
            return writeAccessDenied(exchange, requestId);
        }

        String method = exchange.getRequest().getMethod().name();
        if (csrfEnabled && PROTECTED_METHODS.contains(method)) {
            return csrfTokenService.matches(exchange,
                            exchange.getRequest().getHeaders().getFirst(CsrfTokenService.HEADER))
                    .flatMap(matches -> matches
                            ? chain.filter(exchange)
                            : writeAccessDenied(exchange, requestId));
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static String requestId(ServerWebExchange exchange) {
        String requestId = exchange.getRequest().getHeaders().getFirst(RequestIdFilter.HEADER);
        return requestId == null || requestId.isBlank() || requestId.length() > 128
                ? UUID.randomUUID().toString()
                : requestId;
    }

    private static String sourceIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) {
            return "unknown";
        }
        return remote.getAddress() == null
                ? remote.getHostString()
                : remote.getAddress().getHostAddress();
    }

    private static Map<String, String> headers(ServerWebExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequest().getHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        return headers;
    }

    private static Mono<Void> writeAccessDenied(ServerWebExchange exchange, String requestId) {
        UnifiedError error = UnifiedError.builder(
                        ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限")
                .requestId(requestId)
                .build();
        byte[] body = ManagementResponses.error(error).getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(ErrorCode.ACCESS_DENIED.httpStatus()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }
}
