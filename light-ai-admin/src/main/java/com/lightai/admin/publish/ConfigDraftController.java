package com.lightai.admin.publish;

import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.RevertAllDraftCommand;
import com.lightai.client.publish.RevertDraftCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 草稿状态、差异查询与撤销接口（BE-037/BE-038，BACKEND_PLAN 4.5.6.1）。
 * 查询：可查看角色；撤销：系统管理员（draft.revert）。
 */
@RestController
public class ConfigDraftController {

    private final DraftStateQueryService queryService;
    private final DraftRevertService revertService;

    public ConfigDraftController(DraftStateQueryService queryService,
                                 DraftRevertService revertService) {
        this.queryService = queryService;
        this.revertService = revertService;
    }

    @GetMapping("/admin/config/draft-state")
    public ResponseEntity<String> draftState(HttpServletRequest request) {
        com.lightai.admin.web.RequestPermissions.require(context(request),
                com.lightai.client.protocol.Permissions.DRAFT_VIEW);
        return json(ManagementResponses.ok(queryService.state(context(request))));
    }

    @GetMapping("/admin/config/draft-changes/summary")
    public ResponseEntity<String> summary(HttpServletRequest request) {
        com.lightai.admin.web.RequestPermissions.require(context(request),
                com.lightai.client.protocol.Permissions.DRAFT_VIEW);
        return json(ManagementResponses.ok(queryService.summary(context(request))));
    }

    @GetMapping("/admin/config/draft-changes")
    public ResponseEntity<String> draftChanges(HttpServletRequest request) {
        com.lightai.admin.web.RequestPermissions.require(context(request),
                com.lightai.client.protocol.Permissions.DRAFT_VIEW);
        var query = com.lightai.admin.query.ListQuerySupport.parse(
                request.getParameter("page"), request.getParameter("page_size"),
                request.getParameter("sort"),
                java.util.Set.of("updated_at", "entity_type", "entity_name", "change_type"),
                "updated_at desc");
        return json(ManagementResponses.ok(queryService.draftChanges(context(request),
                request.getParameter("keyword"),
                multi(request, "entity_type"),
                multi(request, "change_type"),
                multi(request, "modified_by"),
                query.page(), query.pageSize())));
    }

    @PostMapping("/admin/config/draft-changes/{entityType}/{entityId}/revert")
    public ResponseEntity<String> revert(@PathVariable String entityType,
                                         @PathVariable String entityId,
                                         @RequestBody String body, HttpServletRequest request) {
        RequestContext context = context(request);
        com.lightai.admin.web.RequestPermissions.require(context,
                com.lightai.client.protocol.Permissions.DRAFT_REVERT);
        RevertDraftCommand command = com.lightai.admin.web.CommandBodies.parse(body,
                RevertDraftCommand.class);
        return json(ManagementResponses.ok(revertService.revertOne(
                context.requestId(), context.authContext().userId(), context.sourceIpMasked(),
                entityType, entityId, command)));
    }

    @PostMapping("/admin/config/draft-changes/revert-all")
    public ResponseEntity<String> revertAll(@RequestBody String body, HttpServletRequest request) {
        RequestContext context = context(request);
        com.lightai.admin.web.RequestPermissions.require(context,
                com.lightai.client.protocol.Permissions.DRAFT_REVERT);
        RevertAllDraftCommand command = com.lightai.admin.web.CommandBodies.parse(body,
                RevertAllDraftCommand.class);
        return json(ManagementResponses.ok(revertService.revertAll(
                context.requestId(), context.authContext().userId(), context.sourceIpMasked(),
                command)));
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        return context;
    }

    private static List<String> multi(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null ? List.of() : Arrays.asList(values);
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
