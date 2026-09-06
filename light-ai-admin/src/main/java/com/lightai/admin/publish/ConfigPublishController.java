package com.lightai.admin.publish;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.ConfigPublishCommand;
import com.lightai.client.publish.ConfigValidateCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置校验、发布与发布历史接口（BE-039/040/042，BACKEND_PLAN 4.5.6.1）。
 * 校验与发布：系统管理员（publish.manage）；历史/快照摘要/实例列表：可查看角色。
 */
@RestController
public class ConfigPublishController {

    private final ConfigValidationService validationService;
    private final ConfigPublishService publishService;

    public ConfigPublishController(ConfigValidationService validationService,
                                   ConfigPublishService publishService) {
        this.validationService = validationService;
        this.publishService = publishService;
    }

    @PostMapping("/admin/config/validate")
    public ResponseEntity<String> validate(@RequestBody String body, HttpServletRequest request) {
        RequestContext context = context(request);
        com.lightai.admin.web.RequestPermissions.require(context,
                com.lightai.client.protocol.Permissions.PUBLISH_MANAGE);
        ConfigValidateCommand command = CommandBodies.parse(body, ConfigValidateCommand.class);
        return json(ManagementResponses.ok(validationService.validate(
                context.requestId(), context.authContext().userId(), context.sourceIpMasked(),
                command)));
    }

    @PostMapping("/admin/config/publish")
    public ResponseEntity<String> publish(@RequestBody String body, HttpServletRequest request) {
        RequestContext context = context(request);
        com.lightai.admin.web.RequestPermissions.require(context,
                com.lightai.client.protocol.Permissions.PUBLISH_MANAGE);
        ConfigPublishCommand command = CommandBodies.parse(body, ConfigPublishCommand.class);
        return json(ManagementResponses.ok(publishService.publish(
                context.requestId(), context.authContext().userId(), context.sourceIpMasked(),
                command)));
    }

    @GetMapping("/admin/config/publish-records")
    public ResponseEntity<String> records(HttpServletRequest request) {
        com.lightai.admin.web.RequestPermissions.require(context(request),
                com.lightai.client.protocol.Permissions.PUBLISH_VIEW);
        var query = com.lightai.admin.query.ListQuerySupport.parse(
                request.getParameter("page"), request.getParameter("page_size"),
                request.getParameter("sort"),
                java.util.Set.of("created_at", "target_snapshot_no", "status"),
                "created_at desc");
        return json(ManagementResponses.ok(publishService.records(
                request.getParameter("status"), request.getParameter("published_by"),
                parseLong(request.getParameter("snapshot_no")),
                request.getParameter("keyword"),
                parseTime(request.getParameter("start_from")),
                parseTime(request.getParameter("start_to")),
                query.page(), query.pageSize())));
    }

    @GetMapping("/admin/config/publish-records/{id}")
    public ResponseEntity<String> recordDetail(@PathVariable String id, HttpServletRequest request) {
        com.lightai.admin.web.RequestPermissions.require(context(request),
                com.lightai.client.protocol.Permissions.PUBLISH_VIEW);
        return json(ManagementResponses.ok(publishService.recordDetail(parseId(id))));
    }

    @GetMapping("/admin/config/snapshots/{snapshotNo}/summary")
    public ResponseEntity<String> snapshotSummary(@PathVariable long snapshotNo,
                                                  HttpServletRequest request) {
        com.lightai.admin.web.RequestPermissions.require(context(request),
                com.lightai.client.protocol.Permissions.PUBLISH_VIEW);
        return json(ManagementResponses.ok(publishService.snapshotSummary(snapshotNo)));
    }

    @GetMapping("/admin/runtime-instances")
    public ResponseEntity<String> runtimeInstances(HttpServletRequest request) {
        com.lightai.admin.web.RequestPermissions.require(context(request),
                com.lightai.client.protocol.Permissions.PUBLISH_VIEW);
        var query = com.lightai.admin.query.ListQuerySupport.parse(
                request.getParameter("page"), request.getParameter("page_size"),
                request.getParameter("sort"),
                java.util.Set.of("last_heartbeat_at", "status", "runtime_mode"),
                "last_heartbeat_at desc");
        return json(ManagementResponses.ok(publishService.runtimeInstances(
                request.getParameter("status"), request.getParameter("runtime_mode"),
                request.getParameter("application"), query.page(), query.pageSize())));
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        return context;
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "snapshot_no 必须是整数",
                    java.util.List.of(new com.lightai.client.error.FieldIssue(
                            "snapshot_no", "INVALID", "snapshot_no 必须是整数")));
        }
    }

    private static OffsetDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "时间参数必须为 ISO-8601",
                    java.util.List.of(new com.lightai.client.error.FieldIssue(
                            "start_at", "INVALID", "时间参数必须为 ISO-8601 格式")));
        }
    }

    private static java.util.UUID parseId(String value) {
        try {
            return java.util.UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "ID 不合法",
                    java.util.List.of(new com.lightai.client.error.FieldIssue(
                            "id", "INVALID", "ID 必须是 UUID")));
        }
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
