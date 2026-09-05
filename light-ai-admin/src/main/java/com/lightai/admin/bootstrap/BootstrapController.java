package com.lightai.admin.bootstrap;

import com.lightai.admin.web.CsrfTokenService;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /admin/bootstrap（BACKEND_PLAN 2.1，C-011）。
 * 已认证管理身份可访问；未认证在拦截器统一 403 ACCESS_DENIED。
 */
@RestController
public class BootstrapController {

    private final BootstrapService bootstrapService;
    private final CsrfTokenService csrfTokenService;
    private final boolean csrfEnabled;

    public BootstrapController(BootstrapService bootstrapService, CsrfTokenService csrfTokenService,
                               boolean csrfEnabled) {
        this.bootstrapService = bootstrapService;
        this.csrfTokenService = csrfTokenService;
        this.csrfEnabled = csrfEnabled;
    }

    @GetMapping("/admin/bootstrap")
    public ResponseEntity<String> bootstrap(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        String csrfToken = csrfEnabled ? csrfTokenService.currentToken(request) : null;
        return ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.ok(bootstrapService.build(context.authContext(), csrfToken)));
    }
}
