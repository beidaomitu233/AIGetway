package com.lightai.admin.channel;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.ResourceIds;
import com.lightai.client.channel.ChannelCredentialCreateCommand;
import com.lightai.client.channel.ChannelCredentialDetail;
import com.lightai.client.channel.ChannelCredentialListItem;
import com.lightai.client.channel.ChannelCredentialRotateCommand;
import com.lightai.client.channel.ChannelCredentialUpdateCommand;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.client.channel.ChannelCheckCommand;
import com.lightai.client.channel.ChannelCheckRecord;
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
public class ChannelCredentialController {

    private final ChannelCredentialService credentialService;

    public ChannelCredentialController(ChannelCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @GetMapping("/admin/channels/{channelId}/credentials")
    public ResponseEntity<String> list(@PathVariable String channelId, HttpServletRequest request) {
        PageResult<ChannelCredentialListItem> page = credentialService.list(context(request),
                ResourceIds.parse(channelId), queryParams(request));
        return json(ManagementResponses.ok(page));
    }

    @PostMapping("/admin/channels/{channelId}/credentials")
    public ResponseEntity<String> create(@PathVariable String channelId, @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        ChannelCredentialCreateCommand command = CommandBodies.parse(body, ChannelCredentialCreateCommand.class);
        ManagementOperationResult<ChannelCredentialDetail> result = credentialService.create(
                context(request), ResourceIds.parse(channelId), command);
        return json(ManagementResponses.ok(result));
    }

    @GetMapping("/admin/channels/{channelId}/credentials/{id}")
    public ResponseEntity<String> detail(@PathVariable String channelId, @PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(credentialService.detail(context(request), channelId, id)));
    }

    @PutMapping("/admin/channels/{channelId}/credentials/{id}")
    public ResponseEntity<String> update(@PathVariable String channelId, @PathVariable String id, @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        ChannelCredentialUpdateCommand command = CommandBodies.parse(body, ChannelCredentialUpdateCommand.class);
        return json(ManagementResponses.ok(credentialService.update(context(request), channelId, id, command)));
    }

    @PostMapping("/admin/channels/{channelId}/credentials/{id}/rotate")
    public ResponseEntity<String> rotate(@PathVariable String channelId, @PathVariable String id, @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        ChannelCredentialRotateCommand command = CommandBodies.parse(body, ChannelCredentialRotateCommand.class);
        return json(ManagementResponses.ok(credentialService.rotate(context(request), channelId, id, command)));
    }

    @PostMapping("/admin/channels/{channelId}/credentials/{id}/enable")
    public ResponseEntity<String> enable(@PathVariable String channelId, @PathVariable String id, @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        com.lightai.client.management.VersionCommand command =
                CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(credentialService.setEnabled(
                context(request), channelId, id, true, command.version())));
    }

    @PostMapping("/admin/channels/{channelId}/credentials/{id}/disable")
    public ResponseEntity<String> disable(@PathVariable String channelId, @PathVariable String id, @RequestBody(required = false) String body,
                                          HttpServletRequest request) {
        com.lightai.client.management.VersionCommand command =
                CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(credentialService.setEnabled(
                context(request), channelId, id, false, command.version())));
    }

    @DeleteMapping("/admin/channels/{channelId}/credentials/{id}")
    public ResponseEntity<String> delete(@PathVariable String channelId, @PathVariable String id, @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        com.lightai.client.management.VersionCommand command =
                CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(credentialService.delete(
                context(request), channelId, id, command.version())));
    }

    @PostMapping("/admin/channels/{channelId}/credentials/{id}/check")
    public ResponseEntity<String> check(@PathVariable String channelId, @PathVariable String id, @RequestBody(required = false) String body,
                                        HttpServletRequest request) {
        // 凭证检测复用检测编排：目标解析到凭证所属 Provider 的模型（可选）
        ChannelCheckCommand command = CommandBodies.parse(body, ChannelCheckCommand.class);
        ChannelCheckRecord record = credentialService.check(context(request), channelId, id, command);
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
