package com.lightai.admin.calls;

import com.lightai.admin.trace.TraceExportService;
import com.lightai.admin.trace.TraceListQueryParser;
import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * V2 调用记录接口（BE-231；BACKEND_PLAN /admin/calls）。
 * /admin/calls/export 为固定静态路由，先于 /{requestId} 匹配。
 * 成功 {data:T}，失败 {error:UnifiedError}；权限、数据范围与脱敏在服务层执行：
 * 列表/详情 trace.view，导出 trace.export，诊断样本 trace.diagnostics。
 */
@RestController
public class CallObservationController {

    private final CallObservationService callService;
    private final TraceExportService traceExportService;

    public CallObservationController(CallObservationService callService,
                                     TraceExportService traceExportService) {
        this.callService = callService;
        this.traceExportService = traceExportService;
    }

    @GetMapping("/admin/calls")
    public ResponseEntity<String> list(HttpServletRequest request) {
        return json(ManagementResponses.ok(
                callService.list(context(request), multiParams(request))));
    }

    @GetMapping("/admin/calls/export")
    public ResponseEntity<StreamingResponseBody> export(HttpServletRequest request) {
        return traceExportService.export(context(request), multiParams(request));
    }

    @GetMapping("/admin/calls/{requestId}")
    public ResponseEntity<String> detail(@PathVariable String requestId,
                                         HttpServletRequest request) {
        boolean includeDiagnostics = Boolean.parseBoolean(request.getParameter("include_diagnostics"));
        return json(ManagementResponses.ok(
                callService.detail(context(request), requestId, includeDiagnostics)));
    }

    private static RequestContext context(HttpServletRequest request) {
        RequestContext context = (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE);
        if (context == null) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
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
