package com.lightai.admin.credential;

import com.lightai.admin.AdminProperties;
import com.lightai.admin.check.ManagementCheckService;
import com.lightai.admin.draft.WriteContext;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.web.Controllers;
import com.lightai.admin.web.ManagementAuthorizer;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.access.CredentialCreateCommand;
import com.lightai.client.access.CredentialRotateCommand;
import com.lightai.client.access.CredentialUpdateCommand;
import com.lightai.client.access.ProviderCheckCommand;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.protocol.RolePermissions;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credential 管理接口（BE-013，4.2.9.2）。
 * 读取：系统管理员与运维人员（credential.view）；写入：系统管理员（credential.manage）；
 * 检测：credential.check。响应永不包含 secret_value/token_hash。
 */
@RestController
public class CredentialController {

    private final CredentialService credentialService;
    private final ManagementCheckService checkService;
    private final AdminProperties properties;

    public CredentialController(CredentialService credentialService, ManagementCheckService checkService,
                                AdminProperties properties) {
        this.credentialService = credentialService;
        this.checkService = checkService;
        this.properties = properties;
    }

    @GetMapping("/admin/credential-pools/{poolId}/credentials")
    public org.springframework.http.ResponseEntity<String> list(
            @PathVariable String poolId,
            @RequestParam(required = false) String health_status,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String page_size,
            @RequestParam(required = false) String sort,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(page, page_size, sort,
                CredentialService.SORTABLE, "name asc");
        return Controllers.ok(credentialService.list(parseId(poolId, "poolId"), health_status, enabled, query));
    }

    @PostMapping("/admin/credential-pools/{poolId}/credentials")
    public org.springframework.http.ResponseEntity<String> create(
            @PathVariable String poolId,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_MANAGE);
        CredentialCreateCommand command = Controllers.parseBody(body, CredentialCreateCommand.class);
        return Controllers.created(credentialService.create(parseId(poolId, "poolId"), command,
                Controllers.writeContext(context, properties)));
    }

    @GetMapping("/admin/credentials/{id}")
    public org.springframework.http.ResponseEntity<String> get(
            @PathVariable String id,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_VIEW);
        return Controllers.ok(credentialService.get(parseId(id, "id")));
    }

    @PutMapping("/admin/credentials/{id}")
    public org.springframework.http.ResponseEntity<String> update(
            @PathVariable String id,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_MANAGE);
        CredentialUpdateCommand command = Controllers.parseBody(body, CredentialUpdateCommand.class);
        return Controllers.ok(credentialService.update(parseId(id, "id"), command,
                Controllers.writeContext(context, properties)));
    }

    @DeleteMapping("/admin/credentials/{id}")
    public org.springframework.http.ResponseEntity<String> delete(
            @PathVariable String id,
            @RequestParam long version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_MANAGE);
        return Controllers.ok(credentialService.delete(parseId(id, "id"), version,
                Controllers.writeContext(context, properties)));
    }

    @PostMapping("/admin/credentials/{id}/rotate")
    public org.springframework.http.ResponseEntity<String> rotate(
            @PathVariable String id,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_MANAGE);
        CredentialRotateCommand command = Controllers.parseBody(body, CredentialRotateCommand.class);
        return Controllers.ok(credentialService.rotate(parseId(id, "id"), command,
                Controllers.writeContext(context, properties)));
    }

    @PostMapping("/admin/credentials/{id}/check")
    public org.springframework.http.ResponseEntity<String> check(
            @PathVariable String id,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_CHECK);
        ProviderCheckCommand command = Controllers.parseBody(body, ProviderCheckCommand.class);
        WriteContext writeContext = Controllers.writeContext(context, properties);
        return Controllers.ok(checkService.check(writeContext.operatorId(), "CREDENTIAL",
                parseId(id, "id"), command));
    }

    @PostMapping("/admin/credentials/{id}/enable")
    public org.springframework.http.ResponseEntity<String> enable(
            @PathVariable String id,
            @RequestParam long version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_MANAGE);
        return Controllers.ok(credentialService.changeEnabled(parseId(id, "id"), true, version, null,
                Controllers.writeContext(context, properties)));
    }

    @PostMapping("/admin/credentials/{id}/disable")
    public org.springframework.http.ResponseEntity<String> disable(
            @PathVariable String id,
            @RequestParam long version,
            @RequestParam(required = false) String confirmed_impact_version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_MANAGE);
        return Controllers.ok(credentialService.changeEnabled(parseId(id, "id"), false, version,
                confirmed_impact_version, Controllers.writeContext(context, properties)));
    }

    @GetMapping("/admin/credentials/{id}/impact")
    public org.springframework.http.ResponseEntity<String> impact(
            @PathVariable String id,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_MANAGE);
        return Controllers.ok(credentialService.impact(parseId(id, "id")));
    }

    @GetMapping("/admin/credentials/{id}/checks")
    public org.springframework.http.ResponseEntity<String> recentChecks(
            @PathVariable String id,
            @RequestParam(defaultValue = "10") int limit,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.CREDENTIAL_VIEW);
        boolean includeProviderRequestId = RolePermissions
                .permissionsFor(Set.copyOf(context.authContext().roles()))
                .contains(Permissions.TRACE_DIAGNOSTICS);
        return Controllers.ok(credentialService.recentChecks(parseId(id, "id"), limit, includeProviderRequestId));
    }

    private static UUID parseId(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(com.lightai.client.error.ErrorCode.FIELD_VALIDATION_FAILED,
                    field + " 不是合法ID", field);
        }
    }
}
