package com.lightai.admin.application;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.application.ApplicationCreateCommand;
import com.lightai.client.application.ApplicationModelsUpdateCommand;
import com.lightai.client.application.ApplicationQuotaAdjustmentCommand;
import com.lightai.client.application.ApplicationQuotaResetCommand;
import com.lightai.client.application.ApplicationQuotaUpdateCommand;
import com.lightai.client.application.ApplicationStatusCommand;
import com.lightai.client.application.ApplicationUpdateCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 企业应用管理 API。 */
@RestController
public final class ApplicationController {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping("/admin/applications")
    public ResponseEntity<String> list(HttpServletRequest request) {
        return json(ManagementResponses.ok(service.list(context(request), queryParams(request))));
    }

    @PostMapping("/admin/applications")
    public ResponseEntity<String> create(@RequestBody(required = false) String body, HttpServletRequest request) {
        ApplicationCreateCommand command = CommandBodies.parse(body, ApplicationCreateCommand.class);
        return ResponseEntity.status(201)
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.ok(service.create(context(request), command)));
    }

    @GetMapping("/admin/applications/{id}")
    public ResponseEntity<String> detail(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(service.detail(context(request), parseId(id))));
    }

    @PutMapping("/admin/applications/{id}")
    public ResponseEntity<String> update(@PathVariable String id, @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        ApplicationUpdateCommand command = CommandBodies.parse(body, ApplicationUpdateCommand.class);
        return json(ManagementResponses.ok(service.update(context(request), parseId(id), command)));
    }

    @GetMapping("/admin/applications/{id}/quota")
    public ResponseEntity<String> quota(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(service.quota(context(request), parseId(id))));
    }

    @GetMapping("/admin/applications/{id}/models")
    public ResponseEntity<String> models(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(service.models(context(request), parseId(id))));
    }

    @PutMapping("/admin/applications/{id}/quota")
    public ResponseEntity<String> updateQuota(@PathVariable String id, @RequestBody(required = false) String body,
                                              HttpServletRequest request) {
        ApplicationQuotaUpdateCommand command = CommandBodies.parse(
                body, ApplicationQuotaUpdateCommand.class);
        return json(ManagementResponses.ok(
                service.updateQuota(context(request), parseId(id), command)));
    }

    @PutMapping("/admin/applications/{id}/models")
    public ResponseEntity<String> updateModels(@PathVariable String id, @RequestBody(required = false) String body,
                                               HttpServletRequest request) {
        ApplicationModelsUpdateCommand command = CommandBodies.parse(
                body, ApplicationModelsUpdateCommand.class);
        return json(ManagementResponses.ok(
                service.updateModels(context(request), parseId(id), command)));
    }

    @GetMapping("/admin/applications/{id}/quota/adjustments")
    public ResponseEntity<String> listAdjustments(@PathVariable String id,
                                                  HttpServletRequest request) {
        return json(ManagementResponses.ok(
                service.listAdjustments(context(request), parseId(id))));
    }

    @PostMapping("/admin/applications/{id}/quota/adjustments")
    public ResponseEntity<String> adjustQuota(@PathVariable String id, @RequestBody(required = false) String body,
                                              HttpServletRequest request) {
        ApplicationQuotaAdjustmentCommand command = CommandBodies.parse(
                body, ApplicationQuotaAdjustmentCommand.class);
        return json(ManagementResponses.ok(
                service.adjustQuota(context(request), parseId(id), command)));
    }

    @PostMapping("/admin/applications/{id}/quota/reset")
    public ResponseEntity<String> resetQuotaUsage(@PathVariable String id, @RequestBody(required = false) String body,
                                                 HttpServletRequest request) {
        ApplicationQuotaResetCommand command = CommandBodies.parse(
                body, ApplicationQuotaResetCommand.class);
        return json(ManagementResponses.ok(
                service.resetQuotaUsage(context(request), parseId(id), command)));
    }

    @PostMapping("/admin/applications/{id}/status")
    public ResponseEntity<String> changeStatus(@PathVariable String id, @RequestBody(required = false) String body,
                                               HttpServletRequest request) {
        ApplicationStatusCommand command = CommandBodies.parse(body, ApplicationStatusCommand.class);
        return json(ManagementResponses.ok(service.changeStatus(context(request), parseId(id), command)));
    }

    /** 应用成员只读列表（PRD 9.2.7）；成员维护方式属待确认事项，本期不提供写接口。 */
    @GetMapping("/admin/applications/{id}/members")
    public ResponseEntity<String> listMembers(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(service.listMembers(context(request), parseId(id))));
    }

    private static UUID parseId(String raw) {
        try {
            UUID id = UUID.fromString(raw);
            if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return id;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "应用 ID 格式不合法", "id");
        }
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        return context;
    }

    private static Map<String, String> queryParams(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values != null && values.length > 0) params.put(name, values[0]);
        });
        return params;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
