package com.lightai.admin.web;

import com.lightai.admin.bootstrap.BootstrapService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** WebFlux 管理端启动信息入口。 */
@RestController
public final class ReactiveBootstrapController {
    private final BootstrapService bootstrapService;
    private final ReactiveCsrfTokenService csrfTokenService;
    private final boolean csrfEnabled;

    public ReactiveBootstrapController(BootstrapService bootstrapService,
                                       ReactiveCsrfTokenService csrfTokenService,
                                       boolean csrfEnabled) {
        this.bootstrapService = bootstrapService;
        this.csrfTokenService = csrfTokenService;
        this.csrfEnabled = csrfEnabled;
    }

    @GetMapping("/admin/bootstrap")
    public Mono<ResponseEntity<String>> bootstrap(ServerWebExchange exchange) {
        RequestContext context = exchange.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            return Mono.error(new IllegalStateException("管理请求上下文缺失"));
        }
        Mono<String> token = csrfEnabled
                ? csrfTokenService.currentToken(exchange)
                : Mono.just("");
        return token.map(value -> ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.ok(bootstrapService.build(
                        context.authContext(), value.isEmpty() ? null : value))));
    }
}
