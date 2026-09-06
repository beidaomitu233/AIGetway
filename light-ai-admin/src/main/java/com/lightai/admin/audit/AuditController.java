package com.lightai.admin.audit;

import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** /admin/audit-logs（BE-045，4.5.6.4）：管理/运维读取与导出；权限在服务层判定。 */
@RestController
public class AuditController {

    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping("/admin/audit-logs")
    public ResponseEntity<String> list(HttpServletRequest request) {
        return json(ManagementResponses.ok(service.list(context(request), queryParams(request))));
    }

    @GetMapping(value = "/admin/audit-logs/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(HttpServletRequest request) {
        String csv = service.exportCsv(context(request), queryParams(request));
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Cache-Control", "no-store")
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/admin/audit-logs/{id}")
    public ResponseEntity<String> detail(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(service.get(context(request), UUID.fromString(id))));
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new com.lightai.client.error.LightAiException(
                    com.lightai.client.error.ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        return context;
    }

    private static java.util.Map<String, String> queryParams(HttpServletRequest request) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values != null && values.length > 0) {
                params.put(name, values[0]);
            }
        });
        return params;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", com.lightai.admin.web.ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
