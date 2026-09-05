package com.lightai.admin.credential;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.credential.CredentialCreateCommand;
import com.lightai.client.credential.CredentialDetail;
import com.lightai.client.credential.CredentialListItem;
import com.lightai.client.credential.CredentialRotateCommand;
import com.lightai.client.credential.CredentialUpdateCommand;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.provider.ProviderCheckCommand;
import com.lightai.client.provider.ProviderCheckRecord;
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
 * Credential 管理接口（BACKEND_PLAN 4.2.9.2，BE-013）。
 * 读取限管理与运维角色；写入/轮换仅系统管理员；响应永不包含 secret_value/token_hash。
 */
@RestController
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping("/admin/credential-pools/{poolId}/credentials")
    public ResponseEntity<String> list(@PathVariable String poolId, HttpServletRequest request) {
        PageResult<CredentialListItem> page = credentialService.list(context(request),
                java.util.UUID.fromString(poolId), queryParams(request));
        return json(ManagementResponses.ok(page));
    }

    @PostMapping("/admin/credential-pools/{poolId}/credentials")
    public ResponseEntity<String> create(@PathVariable String poolId, @RequestBody String body,
                                         HttpServletRequest request) {
        CredentialCreateCommand command = CommandBodies.parse(body, CredentialCreateCommand.class);
        ManagementOperationResult<CredentialDetail> result = credentialService.create(
                context(request), java.util.UUID.fromString(poolId), command);
        return json(ManagementResponses.ok(result));
    }

    @GetMapping("/admin/credentials/{id}")
    public ResponseEntity<String> detail(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(credentialService.detail(context(request), id)));
    }

    @PutMapping("/admin/credentials/{id}")
    public ResponseEntity<String> update(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        CredentialUpdateCommand command = CommandBodies.parse(body, CredentialUpdateCommand.class);
        return json(ManagementResponses.ok(credentialService.update(context(request), id, command)));
    }

    @PostMapping("/admin/credentials/{id}/rotate")
    public ResponseEntity<String> rotate(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        CredentialRotateCommand command = CommandBodies.parse(body, CredentialRotateCommand.class);
        return json(ManagementResponses.ok(credentialService.rotate(context(request), id, command)));
    }

    @PostMapping("/admin/credentials/{id}/enable")
    public ResponseEntity<String> enable(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        com.lightai.client.management.VersionCommand command =
                CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(credentialService.setEnabled(
                context(request), id, true, command.version())));
    }

    @PostMapping("/admin/credentials/{id}/disable")
    public ResponseEntity<String> disable(@PathVariable String id, @RequestBody String body,
                                          HttpServletRequest request) {
        com.lightai.client.management.VersionCommand command =
                CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(credentialService.setEnabled(
                context(request), id, false, command.version())));
    }

    @DeleteMapping("/admin/credentials/{id}")
    public ResponseEntity<String> delete(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        com.lightai.client.management.VersionCommand command =
                CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(credentialService.delete(
                context(request), id, command.version())));
    }

    @PostMapping("/admin/credentials/{id}/check")
    public ResponseEntity<String> check(@PathVariable String id, @RequestBody String body,
                                        HttpServletRequest request) {
        // 凭证检测复用检测编排：目标解析到凭证所属 Provider 的模型（可选）
        ProviderCheckCommand command = CommandBodies.parse(body, ProviderCheckCommand.class);
        ProviderCheckRecord record = credentialService.check(context(request), id, command);
        return json(ManagementResponses.ok(record));
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
