package com.lightai.admin.pool;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.impact.ImpactAnalysis;
import com.lightai.client.management.ImpactConfirmCommand;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.management.VersionCommand;
import com.lightai.client.paging.PageResult;
import com.lightai.client.pool.CredentialPoolDetail;
import com.lightai.client.pool.CredentialPoolListItem;
import com.lightai.client.pool.PoolSaveCommand;
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
 * 凭证池管理接口（BACKEND_PLAN 4.2.9.2，BE-011/012）。
 * 响应不含任何 Secret；写操作仅系统管理员。
 */
@RestController
public class PoolController {

    private final PoolService poolService;

    public PoolController(PoolService poolService) {
        this.poolService = poolService;
    }

    @GetMapping("/admin/credential-pools")
    public ResponseEntity<String> list(HttpServletRequest request) {
        PageResult<CredentialPoolListItem> page = poolService.list(context(request), queryParams(request));
        return json(ManagementResponses.ok(page));
    }

    @GetMapping("/admin/credential-pools/{id}")
    public ResponseEntity<String> detail(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(poolService.detail(context(request), id)));
    }

    @PostMapping("/admin/credential-pools")
    public ResponseEntity<String> create(@RequestBody String body, HttpServletRequest request) {
        PoolSaveCommand command = CommandBodies.parse(body, PoolSaveCommand.class);
        return json(ManagementResponses.ok(poolService.create(context(request), command)));
    }

    @PutMapping("/admin/credential-pools/{id}")
    public ResponseEntity<String> update(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        PoolSaveCommand command = CommandBodies.parse(body, PoolSaveCommand.class);
        return json(ManagementResponses.ok(poolService.update(context(request), id, command)));
    }

    @GetMapping("/admin/credential-pools/{id}/impact")
    public ResponseEntity<String> impact(@PathVariable String id, HttpServletRequest request) {
        String operation = request.getParameter("operation");
        ImpactAnalysis analysis = poolService.impact(context(request), id, operation);
        return json(ManagementResponses.ok(analysis));
    }

    @PostMapping("/admin/credential-pools/{id}/enable")
    public ResponseEntity<String> enable(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        VersionCommand command = CommandBodies.parse(body, VersionCommand.class);
        return json(ManagementResponses.ok(poolService.setEnabled(
                context(request), id, true, command.version(), null)));
    }

    @PostMapping("/admin/credential-pools/{id}/disable")
    public ResponseEntity<String> disable(@PathVariable String id, @RequestBody String body,
                                          HttpServletRequest request) {
        ImpactConfirmCommand command = CommandBodies.parse(body, ImpactConfirmCommand.class);
        return json(ManagementResponses.ok(poolService.setEnabled(
                context(request), id, false, command.version(), command.confirmedImpactVersion())));
    }

    @DeleteMapping("/admin/credential-pools/{id}")
    public ResponseEntity<String> delete(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        ImpactConfirmCommand command = CommandBodies.parse(body, ImpactConfirmCommand.class);
        return json(ManagementResponses.ok(poolService.delete(
                context(request), id, command.version(), command.confirmedImpactVersion())));
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
