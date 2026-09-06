package com.lightai.admin.accesscred;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.security.AccessCredentialCreateCommand;
import com.lightai.client.security.AccessCredentialRotateCommand;
import com.lightai.client.security.AccessCredentialSecretResult;
import com.lightai.client.security.AccessCredentialUpdateCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** /admin/access-credentials（BE-044，4.5.6.3）：读取 access.view；写入 access.manage。 */
@RestController
public class AccessCredentialController {

    private final AccessCredentialService service;

    public AccessCredentialController(AccessCredentialService service) {
        this.service = service;
    }

    @GetMapping("/admin/access-credentials")
    public ResponseEntity<String> list(HttpServletRequest request) {
        return json(ManagementResponses.ok(service.list(context(request), queryParams(request))));
    }

    @PostMapping("/admin/access-credentials")
    public ResponseEntity<String> create(@RequestBody String body, HttpServletRequest request) {
        AccessCredentialCreateCommand command = CommandBodies.parse(body, AccessCredentialCreateCommand.class);
        AccessCredentialSecretResult result = service.create(context(request), command);
        return ResponseEntity.status(201)
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.ok(result));
    }

    @GetMapping("/admin/access-credentials/{id}")
    public ResponseEntity<String> detail(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(service.get(context(request), UUID.fromString(id))));
    }

    @PutMapping("/admin/access-credentials/{id}")
    public ResponseEntity<String> update(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        AccessCredentialUpdateCommand command = CommandBodies.parse(body, AccessCredentialUpdateCommand.class);
        return json(ManagementResponses.ok(service.update(UUID.fromString(id), command, context(request))));
    }

    @PostMapping("/admin/access-credentials/{id}/rotate")
    public ResponseEntity<String> rotate(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        AccessCredentialRotateCommand command = CommandBodies.parse(body, AccessCredentialRotateCommand.class);
        return json(ManagementResponses.ok(service.rotate(UUID.fromString(id), command, context(request))));
    }

    @PostMapping("/admin/access-credentials/{id}/enable")
    public ResponseEntity<String> enable(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(service.changeEnabled(UUID.fromString(id),
                true, versionParam(request), context(request))));
    }

    @PostMapping("/admin/access-credentials/{id}/disable")
    public ResponseEntity<String> disable(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(service.changeEnabled(UUID.fromString(id),
                false, versionParam(request), context(request))));
    }

    @DeleteMapping("/admin/access-credentials/{id}")
    public ResponseEntity<String> delete(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(service.delete(UUID.fromString(id),
                versionParam(request), request.getParameter("reason"), context(request))));
    }

    private static long versionParam(HttpServletRequest request) {
        return Long.parseLong(request.getParameter("version"));
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

    private static java.util.Map<String, String> queryParams(HttpServletRequest request) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values != null && values.length > 0) {
                params.put(name, values[0]);
            }
        });
        return params;
    }

    }
