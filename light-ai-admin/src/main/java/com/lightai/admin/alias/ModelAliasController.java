package com.lightai.admin.alias;

import com.lightai.admin.AdminProperties;
import com.lightai.admin.draft.WriteContext;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.web.Controllers;
import com.lightai.admin.web.ManagementAuthorizer;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.access.CandidateReorderCommand;
import com.lightai.client.access.ModelAliasCreateCommand;
import com.lightai.client.access.ModelAliasUpdateCommand;
import com.lightai.client.access.ProviderCheckCommand;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
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
 * Model Alias 与候选管理接口（BE-016/017/018，4.2.9.4）。
 * 读取 alias.view；写入 alias.manage；探测 provider.check。
 */
@RestController
public class ModelAliasController {

    private final ModelAliasService aliasService;
    private final RouteCandidateService candidateService;
    private final AdminProperties properties;

    public ModelAliasController(ModelAliasService aliasService, RouteCandidateService candidateService,
                                AdminProperties properties) {
        this.aliasService = aliasService;
        this.candidateService = candidateService;
        this.properties = properties;
    }

    @GetMapping("/admin/model-aliases")
    public org.springframework.http.ResponseEntity<String> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean support_stream,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String page_size,
            @RequestParam(required = false) String sort,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(page, page_size, sort,
                ModelAliasService.SORTABLE, "alias asc");
        return Controllers.ok(aliasService.list(keyword, enabled, support_stream, query));
    }

    @PostMapping("/admin/model-aliases")
    public org.springframework.http.ResponseEntity<String> create(
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_MANAGE);
        ModelAliasCreateCommand command = Controllers.parseBody(body, ModelAliasCreateCommand.class);
        return Controllers.created(aliasService.create(command, Controllers.writeContext(context, properties)));
    }

    @GetMapping("/admin/model-aliases/{id}")
    public org.springframework.http.ResponseEntity<String> get(
            @PathVariable String id,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_VIEW);
        return Controllers.ok(aliasService.get(parseId(id, "id")));
    }

    @PutMapping("/admin/model-aliases/{id}")
    public org.springframework.http.ResponseEntity<String> update(
            @PathVariable String id,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_MANAGE);
        ModelAliasUpdateCommand command = Controllers.parseBody(body, ModelAliasUpdateCommand.class);
        return Controllers.ok(aliasService.update(parseId(id, "id"), command,
                Controllers.writeContext(context, properties)));
    }

    @DeleteMapping("/admin/model-aliases/{id}")
    public org.springframework.http.ResponseEntity<String> delete(
            @PathVariable String id,
            @RequestParam long version,
            @RequestParam String confirmed_impact_version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_MANAGE);
        return Controllers.ok(aliasService.delete(parseId(id, "id"), version, confirmed_impact_version,
                Controllers.writeContext(context, properties)));
    }

    @GetMapping("/admin/model-aliases/{id}/impact")
    public org.springframework.http.ResponseEntity<String> impact(
            @PathVariable String id,
            @RequestParam String operation,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_MANAGE);
        return Controllers.ok(aliasService.impact(parseId(id, "id")));
    }

    @PostMapping("/admin/model-aliases/{id}/enable")
    public org.springframework.http.ResponseEntity<String> enable(
            @PathVariable String id,
            @RequestParam long version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_MANAGE);
        return Controllers.ok(aliasService.changeEnabled(parseId(id, "id"), true, version, null,
                Controllers.writeContext(context, properties)));
    }

    @PostMapping("/admin/model-aliases/{id}/disable")
    public org.springframework.http.ResponseEntity<String> disable(
            @PathVariable String id,
            @RequestParam long version,
            @RequestParam String confirmed_impact_version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_MANAGE);
        return Controllers.ok(aliasService.changeEnabled(parseId(id, "id"), false, version,
                confirmed_impact_version, Controllers.writeContext(context, properties)));
    }

    @GetMapping("/admin/model-aliases/{id}/candidates")
    public org.springframework.http.ResponseEntity<String> candidates(
            @PathVariable String id,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_VIEW);
        return Controllers.ok(aliasService.candidates(parseId(id, "id")));
    }

    @PutMapping("/admin/model-aliases/{id}/candidates/reorder")
    public org.springframework.http.ResponseEntity<String> reorder(
            @PathVariable String id,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_MANAGE);
        CandidateReorderCommand command = Controllers.parseBody(body, CandidateReorderCommand.class);
        return Controllers.ok(aliasService.reorder(parseId(id, "id"), command,
                Controllers.writeContext(context, properties)));
    }

    @PostMapping("/admin/route-candidates/{id}/check")
    public org.springframework.http.ResponseEntity<String> checkCandidate(
            @PathVariable String id,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.PROVIDER_CHECK);
        ProviderCheckCommand command = Controllers.parseBody(body, ProviderCheckCommand.class);
        return Controllers.ok(candidateService.check(parseId(id, "id"), command,
                Controllers.writeContext(context, properties)));
    }

    @PutMapping("/admin/route-candidates/{id}")
    public org.springframework.http.ResponseEntity<String> updateCandidate(
            @PathVariable String id,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_MANAGE);
        com.lightai.client.access.RouteCandidateUpdateCommand command =
                Controllers.parseBody(body, com.lightai.client.access.RouteCandidateUpdateCommand.class);
        return Controllers.ok(candidateService.update(parseId(id, "id"), command,
                Controllers.writeContext(context, properties)));
    }

    @DeleteMapping("/admin/route-candidates/{id}")
    public org.springframework.http.ResponseEntity<String> deleteCandidate(
            @PathVariable String id,
            @RequestParam long version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.ALIAS_MANAGE);
        return Controllers.ok(candidateService.delete(parseId(id, "id"), version,
                Controllers.writeContext(context, properties)));
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
