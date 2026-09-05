package com.lightai.admin.alias;

import com.lightai.admin.web.CommandBodies;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.alias.ModelAliasDetail;
import com.lightai.client.alias.ModelAliasSaveCommand;
import com.lightai.client.alias.ReorderCommand;
import com.lightai.client.alias.RouteCandidateDetail;
import com.lightai.client.alias.RouteCandidateSaveCommand;
import com.lightai.client.management.ImpactConfirmCommand;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.management.VersionCommand;
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

import java.util.List;

/**
 * Model Alias 与候选管理接口（BACKEND_PLAN 4.2.9.4，BE-016/017/018）。
 * 查看：可查看角色（开发人员数据范围按授权 Alias 过滤，BE-P06 联调）；
 * 写入：系统管理员。
 */
@RestController
public class ModelAliasController {

    private final ModelAliasService aliasService;
    private final RouteCandidateService candidateService;

    public ModelAliasController(ModelAliasService aliasService, RouteCandidateService candidateService) {
        this.aliasService = aliasService;
        this.candidateService = candidateService;
    }

    @GetMapping("/admin/model-aliases")
    public ResponseEntity<String> list(HttpServletRequest request) {
        PageResult<ModelAliasDetail> page = aliasService.list(context(request), queryParams(request));
        return json(ManagementResponses.ok(page));
    }

    @PostMapping("/admin/model-aliases")
    public ResponseEntity<String> create(@RequestBody String body, HttpServletRequest request) {
        ModelAliasSaveCommand command = CommandBodies.parse(body, ModelAliasSaveCommand.class);
        return json(ManagementResponses.ok(aliasService.create(context(request), command)));
    }

    @GetMapping("/admin/model-aliases/{id}")
    public ResponseEntity<String> detail(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(aliasService.detail(context(request), id)));
    }

    @PutMapping("/admin/model-aliases/{id}")
    public ResponseEntity<String> update(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        ModelAliasSaveCommand command = CommandBodies.parse(body, ModelAliasSaveCommand.class);
        return json(ManagementResponses.ok(aliasService.update(context(request), id, command)));
    }

    @GetMapping("/admin/model-aliases/{id}/impact")
    public ResponseEntity<String> impact(@PathVariable String id, HttpServletRequest request) {
        return json(ManagementResponses.ok(aliasService.impact(context(request), id,
                request.getParameter("operation"))));
    }

    @PostMapping("/admin/model-aliases/{id}/enable")
    public ResponseEntity<String> enable(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        VersionCommand command = CommandBodies.parse(body, VersionCommand.class);
        return json(ManagementResponses.ok(aliasService.setEnabled(
                context(request), id, true, command.version(), null)));
    }

    @PostMapping("/admin/model-aliases/{id}/disable")
    public ResponseEntity<String> disable(@PathVariable String id, @RequestBody String body,
                                          HttpServletRequest request) {
        ImpactConfirmCommand command = CommandBodies.parse(body, ImpactConfirmCommand.class);
        return json(ManagementResponses.ok(aliasService.setEnabled(
                context(request), id, false, command.version(), command.confirmedImpactVersion())));
    }

    @DeleteMapping("/admin/model-aliases/{id}")
    public ResponseEntity<String> delete(@PathVariable String id, @RequestBody String body,
                                         HttpServletRequest request) {
        ImpactConfirmCommand command = CommandBodies.parse(body, ImpactConfirmCommand.class);
        return json(ManagementResponses.ok(aliasService.delete(
                context(request), id, command.version(), command.confirmedImpactVersion())));
    }

    // ---------- 候选（BE-017/018） ----------

    @GetMapping("/admin/model-aliases/{id}/candidates")
    public ResponseEntity<String> candidates(@PathVariable String id, HttpServletRequest request) {
        List<RouteCandidateDetail> candidates = candidateService.candidates(context(request), id);
        return json(ManagementResponses.ok(candidates));
    }

    @PostMapping("/admin/model-aliases/{id}/candidates")
    public ResponseEntity<String> createCandidate(@PathVariable String id, @RequestBody String body,
                                                  HttpServletRequest request) {
        RouteCandidateSaveCommand command = CommandBodies.parse(body, RouteCandidateSaveCommand.class);
        return json(ManagementResponses.ok(candidateService.create(context(request), id, command)));
    }

    @PutMapping("/admin/model-aliases/{id}/candidates/reorder")
    public ResponseEntity<String> reorder(@PathVariable String id, @RequestBody String body,
                                          HttpServletRequest request) {
        ReorderCommand command = CommandBodies.parse(body, ReorderCommand.class);
        return json(ManagementResponses.ok(candidateService.reorder(context(request), id, command)));
    }

    @PutMapping("/admin/route-candidates/{id}")
    public ResponseEntity<String> updateCandidate(@PathVariable String id, @RequestBody String body,
                                                  HttpServletRequest request) {
        RouteCandidateSaveCommand command = CommandBodies.parse(body, RouteCandidateSaveCommand.class);
        return json(ManagementResponses.ok(candidateService.update(context(request), id, command)));
    }

    @DeleteMapping("/admin/route-candidates/{id}")
    public ResponseEntity<String> deleteCandidate(@PathVariable String id, @RequestBody String body,
                                                  HttpServletRequest request) {
        VersionCommand command = CommandBodies.parse(body, VersionCommand.class);
        return json(ManagementResponses.ok(candidateService.delete(
                context(request), id, command.version())));
    }

    @PostMapping("/admin/route-candidates/{id}/check")
    public ResponseEntity<String> checkCandidate(@PathVariable String id, @RequestBody String body,
                                                 HttpServletRequest request) {
        ProviderCheckCommand command = CommandBodies.parse(body, ProviderCheckCommand.class);
        return json(ManagementResponses.ok(candidateService.probe(context(request), id, command)));
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
