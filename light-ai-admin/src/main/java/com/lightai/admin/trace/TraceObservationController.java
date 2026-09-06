package com.lightai.admin.trace;

import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.paging.PageResult;
import com.lightai.client.trace.TraceDetail;
import com.lightai.client.trace.TraceListItem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 调用观测 Trace 接口（BE-031/032/036）。
 * /admin/traces/export 为固定静态路由，先于 /{traceId} 匹配；
 * 成功 {data:T}，失败 {error:UnifiedError}，CSV 导出为流式附件。
 */
@RestController
public class TraceObservationController {

    private final TraceService traceService;
    private final TraceDetailService traceDetailService;
    private final TraceExportService traceExportService;

    public TraceObservationController(TraceService traceService,
                                      TraceDetailService traceDetailService,
                                      TraceExportService traceExportService) {
        this.traceService = traceService;
        this.traceDetailService = traceDetailService;
        this.traceExportService = traceExportService;
    }

    @GetMapping("/admin/traces")
    public ResponseEntity<String> list(HttpServletRequest request) {
        PageResult<TraceListItem> page = traceService.list(context(request), multiParams(request));
        return json(ManagementResponses.ok(page));
    }

    @GetMapping("/admin/traces/export")
    public ResponseEntity<StreamingResponseBody> export(HttpServletRequest request) {
        return traceExportService.export(context(request), multiParams(request));
    }

    @GetMapping("/admin/traces/{traceId}")
    public ResponseEntity<String> detail(@PathVariable String traceId, HttpServletRequest request) {
        boolean includeDiagnostics = Boolean.parseBoolean(request.getParameter("include_diagnostics"));
        TraceDetail detail = traceDetailService.detail(context(request), traceId, includeDiagnostics);
        return json(ManagementResponses.ok(detail));
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
        return TraceListQueryParser.toMultiMap(request.getParameterMap());
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(body);
    }
}
