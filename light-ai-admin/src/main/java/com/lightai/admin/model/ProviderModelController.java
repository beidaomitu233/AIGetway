package com.lightai.admin.model;

import com.lightai.admin.AdminProperties;
import com.lightai.admin.batch.BatchCheckService;
import com.lightai.admin.check.ManagementCheckService;
import com.lightai.admin.draft.WriteContext;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.web.Controllers;
import com.lightai.admin.web.ManagementAuthorizer;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.access.BatchCheckCommand;
import com.lightai.client.access.ProviderCheckCommand;
import com.lightai.client.access.ProviderModelCommand;
import com.lightai.client.access.ProviderModelImportCommand;
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
 * Provider Model 管理接口（BE-014/015，4.2.9.3）。
 * 读取 model.view；写入 model.manage；导入 model.import；
 * 检测 provider.check（4.2.2 矩阵：Provider/模型/别名检测归运维与管理）。
 */
@RestController
public class ProviderModelController {

    private final ProviderModelService modelService;
    private final BatchCheckService batchCheckService;
    private final ManagementCheckService checkService;
    private final AdminProperties properties;

    public ProviderModelController(ProviderModelService modelService, BatchCheckService batchCheckService,
                                   ManagementCheckService checkService, AdminProperties properties) {
        this.modelService = modelService;
        this.batchCheckService = batchCheckService;
        this.checkService = checkService;
        this.properties = properties;
    }

    @GetMapping("/admin/provider-models")
    public org.springframework.http.ResponseEntity<String> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String provider_id,
            @RequestParam(required = false) String connection_status,
            @RequestParam(required = false) Boolean support_stream,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String page_size,
            @RequestParam(required = false) String sort,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_VIEW);
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(page, page_size, sort,
                ProviderModelService.SORTABLE, "display_name asc");
        return Controllers.ok(modelService.list(keyword, parseId(provider_id, "provider_id"),
                connection_status, support_stream, enabled, query));
    }

    @PostMapping("/admin/provider-models")
    public org.springframework.http.ResponseEntity<String> create(
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_MANAGE);
        ProviderModelCommand command = Controllers.parseBody(body, ProviderModelCommand.class);
        return Controllers.created(modelService.create(command, Controllers.writeContext(context, properties)));
    }

    @GetMapping("/admin/provider-models/{id}")
    public org.springframework.http.ResponseEntity<String> get(
            @PathVariable String id,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_VIEW);
        return Controllers.ok(modelService.get(parseId(id, "id")));
    }

    @PutMapping("/admin/provider-models/{id}")
    public org.springframework.http.ResponseEntity<String> update(
            @PathVariable String id,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_MANAGE);
        ProviderModelCommand command = Controllers.parseBody(body, ProviderModelCommand.class);
        return Controllers.ok(modelService.update(parseId(id, "id"), command,
                Controllers.writeContext(context, properties)));
    }

    @DeleteMapping("/admin/provider-models/{id}")
    public org.springframework.http.ResponseEntity<String> delete(
            @PathVariable String id,
            @RequestParam long version,
            @RequestParam String confirmed_impact_version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_MANAGE);
        return Controllers.ok(modelService.delete(parseId(id, "id"), version, confirmed_impact_version,
                Controllers.writeContext(context, properties)));
    }

    @GetMapping("/admin/provider-models/{id}/impact")
    public org.springframework.http.ResponseEntity<String> impact(
            @PathVariable String id,
            @RequestParam String operation,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_MANAGE);
        return Controllers.ok(modelService.impact(parseId(id, "id")));
    }

    @PostMapping("/admin/provider-models/{id}/enable")
    public org.springframework.http.ResponseEntity<String> enable(
            @PathVariable String id,
            @RequestParam long version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_MANAGE);
        return Controllers.ok(modelService.changeEnabled(parseId(id, "id"), true, version, null,
                Controllers.writeContext(context, properties)));
    }

    @PostMapping("/admin/provider-models/{id}/disable")
    public org.springframework.http.ResponseEntity<String> disable(
            @PathVariable String id,
            @RequestParam long version,
            @RequestParam String confirmed_impact_version,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_MANAGE);
        return Controllers.ok(modelService.changeEnabled(parseId(id, "id"), false, version,
                confirmed_impact_version, Controllers.writeContext(context, properties)));
    }

    @PostMapping("/admin/provider-models/{id}/check")
    public org.springframework.http.ResponseEntity<String> check(
            @PathVariable String id,
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.PROVIDER_CHECK);
        ProviderCheckCommand command = Controllers.parseBody(body, ProviderCheckCommand.class);
        WriteContext writeContext = Controllers.writeContext(context, properties);
        return Controllers.ok(checkService.check(writeContext.operatorId(), "PROVIDER_MODEL",
                parseId(id, "id"), command));
    }

    @GetMapping("/admin/providers/{id}/available-models")
    public org.springframework.http.ResponseEntity<String> availableModels(
            @PathVariable String id,
            @RequestParam String source,
            @RequestParam(required = false) String credential_id,
            @RequestParam(required = false) String keyword,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_IMPORT);
        return Controllers.ok(modelService.availableModels(parseId(id, "id"), source,
                parseId(credential_id, "credential_id"), keyword));
    }

    @PostMapping("/admin/provider-models/import")
    public org.springframework.http.ResponseEntity<String> importModels(
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.MODEL_IMPORT);
        ProviderModelImportCommand command = Controllers.parseBody(body, ProviderModelImportCommand.class);
        return Controllers.ok(modelService.importModels(command, Controllers.writeContext(context, properties)));
    }

    @PostMapping("/admin/provider-models/batch-check")
    public org.springframework.http.ResponseEntity<String> batchCheck(
            @RequestBody String body,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.PROVIDER_CHECK);
        BatchCheckCommand command = Controllers.parseBody(body, BatchCheckCommand.class);
        WriteContext writeContext = Controllers.writeContext(context, properties);
        java.util.UUID jobId = batchCheckService.create(command, writeContext.operatorId(), writeContext.requestId());
        return Controllers.accepted(java.util.Map.of("job_id", jobId.toString()));
    }

    @GetMapping("/admin/batch-check-jobs/{id}")
    public org.springframework.http.ResponseEntity<String> batchJob(
            @PathVariable String id,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.PROVIDER_CHECK);
        return Controllers.ok(batchCheckService.get(parseId(id, "id")));
    }

    @PostMapping("/admin/batch-check-jobs/{id}/cancel")
    public org.springframework.http.ResponseEntity<String> cancelBatchJob(
            @PathVariable String id,
            org.springframework.web.context.request.WebRequest request) {
        RequestContext context = Controllers.context(request);
        ManagementAuthorizer.require(context, Permissions.PROVIDER_CHECK);
        return Controllers.ok(batchCheckService.cancel(parseId(id, "id")));
    }

    private static UUID parseId(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(com.lightai.client.error.ErrorCode.FIELD_VALIDATION_FAILED,
                    field + " 不是合法ID", field);
        }
    }
}
