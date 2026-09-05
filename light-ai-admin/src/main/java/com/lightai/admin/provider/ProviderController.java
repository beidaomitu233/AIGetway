package com.lightai.admin.provider;

import com.lightai.admin.check.ProviderCheckService;
import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.impact.ImpactAnalysis;
import com.lightai.client.management.ImpactConfirmCommand;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.management.VersionCommand;
import com.lightai.client.paging.PageResult;
import com.lightai.client.provider.ProviderCheckCommand;
import com.lightai.client.provider.ProviderCheckRecord;
import com.lightai.client.provider.ProviderDetail;
import com.lightai.client.provider.ProviderListItem;
import com.lightai.client.provider.ProviderSaveCommand;
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
 * Provider 管理接口（BACKEND_PLAN 4.2.9.1，BE-007~010）。
 * 权限：查看类接口可查看角色均可；写入与影响仅系统管理员；检测管理+运维。
 * 成功 {data:T}，失败 {error:UnifiedError}；响应体统一走协议序列化。
 */
@RestController
public class ProviderController {

    private final ProviderService providerService;
    private final ProviderCheckService providerCheckService;

    public ProviderController(ProviderService providerService, ProviderCheckService providerCheckService) {
        this.providerService = providerService;
        this.providerCheckService = providerCheckService;
    }

    @GetMapping("/admin/providers")
    public ResponseEntity<String> list(HttpServletRequest request) {
        PageResult<ProviderListItem> page = providerService.list(context(request), queryParams(request));
        return json(ManagementResponses.ok(page));
    }

    @GetMapping("/admin/providers/{id}")
    public ResponseEntity<String> detail(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(providerService.detail(context(request), id)));
    }

    @PostMapping("/admin/providers")
    public ResponseEntity<String> create(@RequestBody String body, HttpServletRequest request) {
        ProviderSaveCommand command = CommandBodies.parse(body, ProviderSaveCommand.class);
        ManagementOperationResult<ProviderDetail> result =
                providerService.create(context(request), command);
        return json(ManagementResponses.ok(result));
    }

    @PutMapping("/admin/providers/{id}")
    public ResponseEntity<String> update(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        ProviderSaveCommand command = CommandBodies.parse(body, ProviderSaveCommand.class);
        return json(ManagementResponses.ok(providerService.update(context(request), id, command)));
    }

    @GetMapping("/admin/providers/{id}/impact")
    public ResponseEntity<String> impact(@PathVariable String id, HttpServletRequest request) {
        String operation = request.getParameter("operation");
        ImpactAnalysis analysis = providerService.impact(context(request), id, operation);
        return json(ManagementResponses.ok(analysis));
    }

    @PostMapping("/admin/providers/{id}/check")
    public ResponseEntity<String> check(@PathVariable String id, @RequestBody String body,
                                        HttpServletRequest request) {
        ProviderCheckCommand command = CommandBodies.parse(body, ProviderCheckCommand.class);
        ProviderCheckRecord record = providerCheckService.check(context(request), id, command);
        return json(ManagementResponses.ok(record));
    }

    @PostMapping("/admin/providers/{id}/enable")
    public ResponseEntity<String> enable(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        VersionCommand command = CommandBodies.parse(body, VersionCommand.class);
        return json(ManagementResponses.ok(providerService.setEnabled(
                context(request), id, true, command.version(), null)));
    }

    @PostMapping("/admin/providers/{id}/disable")
    public ResponseEntity<String> disable(@PathVariable String id, @RequestBody String body,
                                          HttpServletRequest request) {
        ImpactConfirmCommand command = CommandBodies.parse(body, ImpactConfirmCommand.class);
        return json(ManagementResponses.ok(providerService.setEnabled(
                context(request), id, false, command.version(), command.confirmedImpactVersion())));
    }

    @DeleteMapping("/admin/providers/{id}")
    public ResponseEntity<String> delete(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        ImpactConfirmCommand command = CommandBodies.parse(body, ImpactConfirmCommand.class);
        return json(ManagementResponses.ok(providerService.delete(
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
