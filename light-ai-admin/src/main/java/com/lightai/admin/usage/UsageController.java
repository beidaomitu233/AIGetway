package com.lightai.admin.usage;

import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.usage.UsageResults.UsageGroupResult;
import com.lightai.client.usage.UsageResults.UsageSummaryResult;
import com.lightai.client.usage.UsageResults.UsageTrendResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Usage 与 Cost 接口（BE-035/036）。
 * summary/trends/groups 对同一组筛选字段返回相同 query_fingerprint；
 * /admin/usage/export 为系统管理员与运维人员的流式 CSV。
 */
@RestController
public class UsageController {

    private final UsageService usageService;
    private final UsageExportService usageExportService;

    public UsageController(UsageService usageService, UsageExportService usageExportService) {
        this.usageService = usageService;
        this.usageExportService = usageExportService;
    }

    @GetMapping("/admin/usage/summary")
    public ResponseEntity<String> summary(HttpServletRequest request) {
        UsageSummaryResult result = usageService.summary(context(request), multiParams(request));
        return json(ManagementResponses.ok(result));
    }

    @GetMapping("/admin/usage/trends")
    public ResponseEntity<String> trends(HttpServletRequest request) {
        UsageTrendResult result = usageService.trends(context(request), multiParams(request));
        return json(ManagementResponses.ok(result));
    }

    @GetMapping("/admin/usage/groups")
    public ResponseEntity<String> groups(HttpServletRequest request) {
        UsageGroupResult result = usageService.groups(context(request), multiParams(request));
        return json(ManagementResponses.ok(result));
    }

    @GetMapping("/admin/usage/export")
    public ResponseEntity<StreamingResponseBody> export(HttpServletRequest request) {
        return usageExportService.export(context(request), multiParams(request));
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new com.lightai.client.error.LightAiException(
                    com.lightai.client.error.ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        return context;
    }

    static Map<String, List<String>> multiParams(HttpServletRequest request) {
        return UsageQueryParser.toMultiMap(request.getParameterMap());
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
