package com.lightai.admin.governance;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.governance.LimitPolicyDetail;
import com.lightai.client.governance.LimitPolicySaveCommand;
import com.lightai.client.governance.ReliabilityPolicyDetail;
import com.lightai.client.governance.ReliabilityPolicySaveCommand;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行治理管理接口（BACKEND_PLAN 4.3.5，BE-021/022/023）。
 * 查看：可查看角色；写入：系统管理员；用量/队列/恢复决策：管理+运维。
 */
@RestController
public class GovernanceController {

    private final GovernanceAdminService governanceService;
    private final CircuitManagementService circuitService;

    public GovernanceController(GovernanceAdminService governanceService,
                                CircuitManagementService circuitService) {
        this.governanceService = governanceService;
        this.circuitService = circuitService;
    }

    // ---------- 限流（BE-021） ----------

    @GetMapping("/admin/limit-policies")
    public ResponseEntity<String> listLimitPolicies(HttpServletRequest request) {
        return json(ManagementResponses.ok(governanceService.listLimitPolicies(
                context(request), queryParams(request))));
    }

    @PostMapping("/admin/limit-policies")
    public ResponseEntity<String> createLimitPolicy(@RequestBody String body,
                                                    HttpServletRequest request) {
        LimitPolicySaveCommand command = CommandBodies.parse(body, LimitPolicySaveCommand.class);
        ManagementOperationResult<LimitPolicyDetail> result =
                governanceService.saveLimitPolicy(context(request), command, null);
        return json(ManagementResponses.ok(result));
    }

    @GetMapping("/admin/limit-policies/{id}")
    public ResponseEntity<String> limitPolicyDetail(@PathVariable String id,
                                                    HttpServletRequest request) {
        return json(ManagementResponses.ok(governanceService.limitPolicyDetail(
                context(request), id)));
    }

    @PutMapping("/admin/limit-policies/{id}")
    public ResponseEntity<String> updateLimitPolicy(@PathVariable String id,
                                                    @RequestBody String body,
                                                    HttpServletRequest request) {
        LimitPolicySaveCommand command = CommandBodies.parse(body, LimitPolicySaveCommand.class);
        return json(ManagementResponses.ok(governanceService.saveLimitPolicy(
                context(request), command, id)));
    }

    @GetMapping("/admin/limit-policies/{id}/usage")
    public ResponseEntity<String> limitUsage(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(governanceService.limitUsage(context(request), id)));
    }

    @PostMapping("/admin/limit-policies/{id}/enable")
    public ResponseEntity<String> enableLimitPolicy(@PathVariable String id,
                                                    @RequestBody String body,
                                                    HttpServletRequest request) {
        var command = CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(governanceService.setLimitPolicyEnabled(
                context(request), id, true, command.version())));
    }

    @PostMapping("/admin/limit-policies/{id}/disable")
    public ResponseEntity<String> disableLimitPolicy(@PathVariable String id,
                                                     @RequestBody String body,
                                                     HttpServletRequest request) {
        var command = CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(governanceService.setLimitPolicyEnabled(
                context(request), id, false, command.version())));
    }

    @DeleteMapping("/admin/limit-policies/{id}")
    public ResponseEntity<String> deleteLimitPolicy(@PathVariable String id,
                                                    @RequestBody String body,
                                                    HttpServletRequest request) {
        var command = CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(governanceService.deleteLimitPolicy(
                context(request), id, command.version())));
    }

    // ---------- 可靠性（BE-022） ----------

    @GetMapping("/admin/reliability-policies")
    public ResponseEntity<String> listReliabilityPolicies(HttpServletRequest request) {
        return json(ManagementResponses.ok(governanceService.listReliabilityPolicies(
                context(request), queryParams(request))));
    }

    @GetMapping("/admin/reliability-policies/default")
    public ResponseEntity<String> reliabilityDefault(HttpServletRequest request) {
        return json(ManagementResponses.ok(governanceService.reliabilityDefault(context(request))));
    }

    @PostMapping("/admin/reliability-policies")
    public ResponseEntity<String> createReliabilityPolicy(@RequestBody String body,
                                                          HttpServletRequest request) {
        ReliabilityPolicySaveCommand command =
                CommandBodies.parse(body, ReliabilityPolicySaveCommand.class);
        return json(ManagementResponses.ok(governanceService.saveReliabilityPolicy(
                context(request), command, null)));
    }

    @GetMapping("/admin/reliability-policies/{id}")
    public ResponseEntity<String> reliabilityDetail(@PathVariable String id,
                                                    HttpServletRequest request) {
        return json(ManagementResponses.ok(governanceService.reliabilityDetail(
                context(request), id)));
    }

    @PutMapping("/admin/reliability-policies/{id}")
    public ResponseEntity<String> updateReliabilityPolicy(@PathVariable String id,
                                                          @RequestBody String body,
                                                          HttpServletRequest request) {
        ReliabilityPolicySaveCommand command =
                CommandBodies.parse(body, ReliabilityPolicySaveCommand.class);
        return json(ManagementResponses.ok(governanceService.saveReliabilityPolicy(
                context(request), command, id)));
    }

    // ---------- 熔断（BE-023） ----------

    @GetMapping("/admin/circuits")
    public ResponseEntity<String> listCircuits(HttpServletRequest request) {
        return json(ManagementResponses.ok(circuitService.list(context(request),
                queryParams(request))));
    }

    @GetMapping("/admin/circuits/{id}")
    public ResponseEntity<String> circuitDetail(@PathVariable String id,
                                                HttpServletRequest request) {
        return json(ManagementResponses.ok(circuitService.detail(context(request), id)));
    }

    @GetMapping("/admin/circuits/{id}/events")
    public ResponseEntity<String> circuitEvents(@PathVariable String id,
                                                HttpServletRequest request) {
        return json(ManagementResponses.ok(circuitService.events(context(request), id,
                queryParams(request))));
    }

    @PostMapping("/admin/circuits/{id}/open")
    public ResponseEntity<String> openCircuit(@PathVariable String id, @RequestBody String body,
                                              HttpServletRequest request) {
        ManualCommand command = CommandBodies.parse(body, ManualCommand.class);
        return json(ManagementResponses.ok(circuitService.applyManual(context(request), id,
                "MANUAL_OPEN", command.reason, command.openSeconds, command.stateVersion)));
    }

    @PostMapping("/admin/circuits/{id}/recover")
    public ResponseEntity<String> recoverCircuit(@PathVariable String id, @RequestBody String body,
                                                 HttpServletRequest request) {
        ManualCommand command = CommandBodies.parse(body, ManualCommand.class);
        return json(ManagementResponses.ok(circuitService.applyManual(context(request), id,
                "MANUAL_RECOVER", command.reason, null, command.stateVersion)));
    }

    public record ManualCommand(String action, String reason, Integer openSeconds,
                                Long stateVersion, Integer timeoutMs) {
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
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
