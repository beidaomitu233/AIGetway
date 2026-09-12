package com.lightai.admin.settings;

import com.lightai.admin.runtimeconfig.RuntimeConfigAdminService;
import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.security.RetentionImpactCommand;
import com.lightai.client.security.RetentionImpactResult;
import com.lightai.client.security.RuntimeConfigUpdateCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * V2 系统设置接口（BE-234；BACKEND_PLAN /admin/settings，PRD 9.10）。
 * 复用 RuntimeConfigAdminService：默认时区、日志留存、诊断采样、网络目标策略
 * 与运行默认值；查看 runtimeconfig.view、修改 runtimeconfig.manage，修改留痕审计。
 * 预算告警阈值依赖 runtime_setting 扩展列（BE-P23-002 登记 DB 协调），暂不虚设字段。
 * /admin/runtime-config 为既有过渡路径，FE-P23 切换后由原负责人移除。
 */
@RestController
public class SettingsController {

    private final RuntimeConfigAdminService service;

    public SettingsController(RuntimeConfigAdminService service) {
        this.service = service;
    }

    @GetMapping("/admin/settings")
    public ResponseEntity<String> get(HttpServletRequest request) {
        return json(ManagementResponses.ok(service.get(context(request))));
    }

    @PutMapping("/admin/settings")
    public ResponseEntity<String> put(@RequestBody String body, HttpServletRequest request) {
        RuntimeConfigUpdateCommand command = CommandBodies.parse(body, RuntimeConfigUpdateCommand.class);
        return json(ManagementResponses.ok(service.put(context(request), command)));
    }

    @PostMapping("/admin/settings/retention-impact")
    public ResponseEntity<String> retentionImpact(@RequestBody String body,
                                                  HttpServletRequest request) {
        RetentionImpactCommand command = CommandBodies.parse(body, RetentionImpactCommand.class);
        RetentionImpactResult result = service.retentionImpact(context(request), command);
        return ResponseEntity.accepted()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.ok(result));
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        return context;
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
