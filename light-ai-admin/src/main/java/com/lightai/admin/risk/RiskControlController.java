package com.lightai.admin.risk;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.risk.RiskPolicyUpdateCommand;
import java.time.Instant;
import java.time.OffsetDateTime;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class RiskControlController {
    private final RiskControlService service;

    public RiskControlController(RiskControlService service) {
        this.service = service;
    }

    @GetMapping("/admin/risk-control/policy")
    public ResponseEntity<String> policy(HttpServletRequest request) {
        return json(ManagementResponses.ok(service.policy(context(request))));
    }

    @PutMapping("/admin/risk-control/policy")
    public ResponseEntity<String> replace(@RequestBody(required = false) String body,
                                          HttpServletRequest request) {
        return json(ManagementResponses.ok(service.replace(context(request),
                CommandBodies.parse(body, RiskPolicyUpdateCommand.class))));
    }

    @GetMapping("/admin/risk-control/events")
    public ResponseEntity<String> events(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false, name = "application_id") String applicationId,
            @RequestParam(required = false, name = "event_type") String eventType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletRequest request) {
        return json(ManagementResponses.ok(service.events(context(request), limit, applicationId,
                eventType, parseTime(from, "from"), parseTime(to, "to"))));
    }

    @GetMapping("/admin/risk-control/applications")
    public ResponseEntity<String> applications(HttpServletRequest request) {
        return json(ManagementResponses.ok(service.applications(context(request))));
    }

    private static Instant parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (RuntimeException invalid) {
                throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "时间格式不合法: " + field);
            }
        }
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        return context;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().header("Content-Type", ManagementResponses.APPLICATION_JSON).body(body);
    }
}
