package com.lightai.admin.application;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.application.ApplicationKeyCreateCommand;
import com.lightai.client.application.ApplicationKeyRevokeCommand;
import com.lightai.client.application.ApplicationKeyRotateCommand;
import com.lightai.client.application.ApplicationKeyStatusCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 应用详情下的密钥签发、轮换和撤销接口。 */
@RestController
@RequestMapping("/admin/applications/{applicationId}/keys")
public final class ApplicationKeyController {

    private final ApplicationKeyService service;

    public ApplicationKeyController(ApplicationKeyService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<String> list(@PathVariable String applicationId, HttpServletRequest request) {
        return json(200, service.list(context(request), parseId(applicationId)));
    }

    @PostMapping
    public ResponseEntity<String> create(
            @PathVariable String applicationId, @RequestBody(required = false) String body,
            HttpServletRequest request) {
        ApplicationKeyCreateCommand command = CommandBodies.parse(body, ApplicationKeyCreateCommand.class);
        return json(201, service.create(context(request), parseId(applicationId), command));
    }

    @PostMapping("/{keyId}/rotate")
    public ResponseEntity<String> rotate(
            @PathVariable String applicationId, @PathVariable String keyId,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        return json(200, service.rotate(context(request), parseId(applicationId), parseId(keyId),
                CommandBodies.parse(body, ApplicationKeyRotateCommand.class)));
    }

    @PostMapping("/{keyId}/status")
    public ResponseEntity<String> changeStatus(
            @PathVariable String applicationId, @PathVariable String keyId,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        return json(200, service.changeStatus(context(request), parseId(applicationId), parseId(keyId),
                CommandBodies.parse(body, ApplicationKeyStatusCommand.class)));
    }

    @PostMapping("/{keyId}/revoke")
    public ResponseEntity<String> revoke(
            @PathVariable String applicationId, @PathVariable String keyId,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        return json(200, service.revoke(context(request), parseId(applicationId), parseId(keyId),
                CommandBodies.parse(body, ApplicationKeyRevokeCommand.class)));
    }

    private static ResponseEntity<String> json(int status, Object data) {
        return ResponseEntity.status(status)
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .body(ManagementResponses.ok(data));
    }

    private static UUID parseId(String raw) {
        try {
            UUID id = UUID.fromString(raw);
            if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "ID 格式不合法", "id");
        }
    }

    private static RequestContext context(HttpServletRequest request) {
        return (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
    }
}
