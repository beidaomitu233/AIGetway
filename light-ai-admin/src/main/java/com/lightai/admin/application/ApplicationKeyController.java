package com.lightai.admin.application;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.application.ApplicationKeyCreateCommand;
import com.lightai.client.application.ApplicationKeyRevokeCommand;
import com.lightai.client.application.ApplicationKeyRotateCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
    public Object list(@PathVariable UUID applicationId, HttpServletRequest request) {
        return service.list(context(request), applicationId);
    }

    @PostMapping
    public ResponseEntity<?> create(
            @PathVariable UUID applicationId, @RequestBody(required = false) String body,
            HttpServletRequest request) {
        ApplicationKeyCreateCommand command = CommandBodies.parse(body, ApplicationKeyCreateCommand.class);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(context(request), applicationId, command));
    }

    @PostMapping("/{keyId}/rotate")
    public Object rotate(
            @PathVariable UUID applicationId, @PathVariable UUID keyId,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        return service.rotate(context(request), applicationId, keyId,
                CommandBodies.parse(body, ApplicationKeyRotateCommand.class));
    }

    @PostMapping("/{keyId}/revoke")
    public Object revoke(
            @PathVariable UUID applicationId, @PathVariable UUID keyId,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        return service.revoke(context(request), applicationId, keyId,
                CommandBodies.parse(body, ApplicationKeyRevokeCommand.class));
    }

    private static RequestContext context(HttpServletRequest request) {
        return (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
    }
}
