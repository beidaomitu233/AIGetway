package com.lightai.admin.runtimeconfig;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.security.RetentionImpactResult;
import com.lightai.client.security.RuntimeConfigUpdateCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** /admin/runtime-config（BE-043）：查看与修改权限在服务层判定；管理写走 CSRF。 */
@RestController
public class RuntimeConfigController {

    private final RuntimeConfigAdminService service;

    public RuntimeConfigController(RuntimeConfigAdminService service) {
        this.service = service;
    }

    @GetMapping("/admin/runtime-config")
    public ResponseEntity<String> get(HttpServletRequest request) {
        return json(ManagementResponses.ok(service.get(context(request))));
    }

    @PutMapping("/admin/runtime-config")
    public ResponseEntity<String> put(@RequestBody String body, HttpServletRequest request) {
        RuntimeConfigUpdateCommand command = CommandBodies.parse(body, RuntimeConfigUpdateCommand.class);
        return json(ManagementResponses.ok(service.put(context(request), command)));
    }

    @PostMapping("/admin/runtime-config/retention-impact")
    public ResponseEntity<String> retentionImpact(@RequestBody String body, HttpServletRequest request) {
        RuntimeConfigUpdateCommand command = CommandBodies.parse(body, RuntimeConfigUpdateCommand.class);
        RetentionImpactResult result = service.retentionImpact(context(request), command);
        return ResponseEntity.accepted()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.ok(result));
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new com.lightai.client.error.LightAiException(
                    com.lightai.client.error.ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        return context;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
