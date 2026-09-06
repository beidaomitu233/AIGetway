package com.lightai.admin.publish;

import com.lightai.admin.web.ManagementResponses;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.ConfigSnapshotContentView;
import com.lightai.client.publish.InstanceLoadReport;
import com.lightai.client.publish.PublishInstanceResultView;
import com.lightai.client.publish.RuntimeHeartbeatResponse;
import com.lightai.client.publish.RuntimeInstanceHeartbeat;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行实例内部接口（BE-041，BACKEND_PLAN 4.5.6.5）。
 * 仅实例服务身份可访问（InternalAuthInterceptor）；心跳/上报按 instance_id 幂等。
 */
@RestController
public class InternalInstanceController {

    private final ConfigPublishService publishService;
    private final InternalInstanceAuth instanceAuth;

    public InternalInstanceController(ConfigPublishService publishService,
                                      InternalInstanceAuth instanceAuth) {
        this.publishService = publishService;
        this.instanceAuth = instanceAuth;
    }

    @PostMapping("/internal/runtime-instances/heartbeat")
    public ResponseEntity<String> heartbeat(@RequestBody String body, HttpServletRequest request) {
        RuntimeInstanceHeartbeat heartbeat =
                com.lightai.admin.web.CommandBodies.parse(body, RuntimeInstanceHeartbeat.class);
        InternalInstanceAuth.requireIdentity(request, heartbeat.instanceId());
        RuntimeHeartbeatResponse response = publishService.heartbeat(heartbeat);
        return json(ManagementResponses.ok(response));
    }

    @GetMapping("/internal/config-snapshots/{snapshotNo}")
    public ResponseEntity<String> snapshot(@PathVariable long snapshotNo,
                                           HttpServletRequest request) {
        String expectedChecksum = request.getParameter("expected_checksum");
        UUID identity = boundIdentity(request);
        ConfigSnapshotContentView content =
                publishService.snapshotContent(identity, snapshotNo, expectedChecksum);
        return json(ManagementResponses.ok(content));
    }

    @PostMapping("/internal/publish-records/{publishId}/instances/{instanceId}/reports")
    public ResponseEntity<String> report(@PathVariable String publishId,
                                         @PathVariable String instanceId,
                                         @RequestBody String body, HttpServletRequest request) {
        InternalInstanceAuth.requireIdentity(request, instanceId);
        InstanceLoadReport report = com.lightai.admin.web.CommandBodies.parse(body,
                InstanceLoadReport.class);
        PublishInstanceResultView result = publishService.applyReport(
                UUID.fromString(publishId), UUID.fromString(instanceId), report);
        return json(ManagementResponses.ok(result));
    }

    private static UUID boundIdentity(HttpServletRequest request) {
        Object bound = request.getAttribute(InternalInstanceAuth.ATTRIBUTE);
        return bound instanceof UUID uuid ? uuid : null;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
