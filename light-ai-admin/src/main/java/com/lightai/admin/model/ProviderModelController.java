package com.lightai.admin.model;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.model.ImportResult;
import com.lightai.client.model.ProviderModelDetail;
import com.lightai.client.model.ProviderModelImportCommand;
import com.lightai.client.model.ProviderModelSaveCommand;
import com.lightai.client.paging.PageResult;
import com.lightai.client.provider.ProviderCheckCommand;
import com.lightai.client.provider.ProviderCheckRecord;
import com.lightai.storage.batch.BatchItemRecord;
import com.lightai.storage.batch.BatchJobRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Provider Model 管理接口（BACKEND_PLAN 4.2.9.3，BE-014/015）。
 * 查看：可查看角色；写入/影响/导入：系统管理员；检测与任务：管理+运维。
 */
@RestController
public class ProviderModelController {

    private final ProviderModelService modelService;
    private final ModelImportService importService;

    public ProviderModelController(ProviderModelService modelService, ModelImportService importService) {
        this.modelService = modelService;
        this.importService = importService;
    }

    @GetMapping("/admin/providers/{providerId}/models")
    public ResponseEntity<String> listByProvider(@PathVariable String providerId,
                                                 HttpServletRequest request) {
        PageResult<ProviderModelDetail> page = modelService.listByProvider(context(request),
                java.util.UUID.fromString(providerId), queryParams(request));
        return json(ManagementResponses.ok(page));
    }

    @PostMapping("/admin/providers/{providerId}/models")
    public ResponseEntity<String> create(@PathVariable String providerId, @RequestBody String body,
                                         HttpServletRequest request) {
        ProviderModelSaveCommand command = CommandBodies.parse(body, ProviderModelSaveCommand.class);
        ManagementOperationResult<ProviderModelDetail> result = modelService.create(
                context(request), java.util.UUID.fromString(providerId), command);
        return json(ManagementResponses.ok(result));
    }

    @GetMapping("/admin/provider-models/{id}")
    public ResponseEntity<String> detail(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(modelService.detail(context(request), id)));
    }

    @PutMapping("/admin/provider-models/{id}")
    public ResponseEntity<String> update(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        ProviderModelSaveCommand command = CommandBodies.parse(body, ProviderModelSaveCommand.class);
        return json(ManagementResponses.ok(modelService.update(context(request), id, command)));
    }

    @GetMapping("/admin/provider-models/{id}/impact")
    public ResponseEntity<String> impact(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(modelService.impact(context(request), id,
                request.getParameter("operation"))));
    }

    @PostMapping("/admin/provider-models/{id}/enable")
    public ResponseEntity<String> enable(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        com.lightai.client.management.VersionCommand command =
                CommandBodies.parse(body, com.lightai.client.management.VersionCommand.class);
        return json(ManagementResponses.ok(modelService.setEnabled(
                context(request), id, true, command.version(), null)));
    }

    @PostMapping("/admin/provider-models/{id}/disable")
    public ResponseEntity<String> disable(@PathVariable String id, @RequestBody String body,
                                          HttpServletRequest request) {
        com.lightai.client.management.ImpactConfirmCommand command =
                CommandBodies.parse(body, com.lightai.client.management.ImpactConfirmCommand.class);
        return json(ManagementResponses.ok(modelService.setEnabled(
                context(request), id, false, command.version(), command.confirmedImpactVersion())));
    }

    @DeleteMapping("/admin/provider-models/{id}")
    public ResponseEntity<String> delete(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        com.lightai.client.management.ImpactConfirmCommand command =
                CommandBodies.parse(body, com.lightai.client.management.ImpactConfirmCommand.class);
        return json(ManagementResponses.ok(modelService.delete(
                context(request), id, command.version(), command.confirmedImpactVersion())));
    }

    @PostMapping("/admin/provider-models/{id}/check")
    public ResponseEntity<String> check(@PathVariable String id, @RequestBody String body,
                                        HttpServletRequest request) {
        ProviderCheckCommand command = CommandBodies.parse(body, ProviderCheckCommand.class);
        return json(ManagementResponses.ok(modelService.check(context(request), id, command)));
    }

    @PostMapping("/admin/provider-models/import")
    public ResponseEntity<String> importModels(@RequestBody String body, HttpServletRequest request) {
        ProviderModelImportCommand command =
                CommandBodies.parse(body, ProviderModelImportCommand.class);
        ImportResult result = importService.importModels(context(request), command);
        return json(ManagementResponses.ok(result));
    }

    @PostMapping("/admin/provider-models/batch-check")
    public ResponseEntity<String> batchCheck(@RequestBody String body, HttpServletRequest request) {
        BatchCheckBody command = CommandBodies.parse(body, BatchCheckBody.class);
        BatchJobRecord job = importService.createBatchCheck(context(request),
                command.getProviderId(), command.getProviderModelIds(), command.getCredentialId(),
                command.getMode(), command.getTimeoutMs());
        return json(ManagementResponses.ok(new JobPayload(
                job.id().toString(), job.status(), job.operatorId(), job.totalCount(),
                job.completedCount(), job.successCount(), job.failureCount(), job.cancelledCount(),
                job.startedAt(), job.endedAt(), job.commandJson(), List.of())));
    }

    @GetMapping("/admin/batch-check-jobs/{id}")
    public ResponseEntity<String> job(@PathVariable String id, HttpServletRequest request) {
        BatchJobRecord job = importService.job(context(request), id);
        List<BatchItemRecord> items = importService.jobItems(context(request), id);
        return json(ManagementResponses.ok(new JobPayload(
                job.id().toString(), job.status(), job.operatorId(), job.totalCount(),
                job.completedCount(), job.successCount(), job.failureCount(), job.cancelledCount(),
                job.startedAt(), job.endedAt(), job.commandJson(),
                items.stream().map(item -> new ItemPayload(item.id().toString(),
                        item.providerModelId().toString(), item.sequence(), item.status(),
                        item.checkRecordId() == null ? null : item.checkRecordId().toString(),
                        item.errorCode())).toList())));
    }

    @PostMapping("/admin/batch-check-jobs/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable String id, HttpServletRequest request) {
        BatchJobRecord job = importService.cancel(context(request), id);
        return json(ManagementResponses.ok(new JobPayload(
                job.id().toString(), job.status(), job.operatorId(), job.totalCount(),
                job.completedCount(), job.successCount(), job.failureCount(), job.cancelledCount(),
                job.startedAt(), job.endedAt(), job.commandJson(), List.of())));
    }

    /** 批量检测命令体（provider_model_ids/credential_id/mode/timeout_ms）。 */
    static final class BatchCheckBody {
        private java.util.UUID providerId;
        private List<java.util.UUID> providerModelIds;
        private java.util.UUID credentialId;
        private String mode;
        private Integer timeoutMs;

        public java.util.UUID getProviderId() {
            return providerId;
        }

        public List<java.util.UUID> getProviderModelIds() {
            return providerModelIds;
        }

        public java.util.UUID getCredentialId() {
            return credentialId;
        }

        public String getMode() {
            return mode;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }
    }

    record JobPayload(String id, String status, String operatorId, int totalCount,
                      int completedCount, int successCount, int failureCount, int cancelledCount,
                      java.time.OffsetDateTime startedAt, java.time.OffsetDateTime endedAt,
                      String command, List<ItemPayload> items) {
    }

    record ItemPayload(String id, String providerModelId, int sequence, String status,
                       String checkRecordId, String errorCode) {
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
