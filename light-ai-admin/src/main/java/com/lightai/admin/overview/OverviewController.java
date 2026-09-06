package com.lightai.admin.overview;

import com.lightai.admin.web.ManagementResponses;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.overview.OverviewResults.OverviewExceptionResult;
import com.lightai.client.overview.OverviewResults.OverviewFilterOptions;
import com.lightai.client.overview.OverviewResults.OverviewSummary;
import com.lightai.client.overview.OverviewResults.OverviewTrendResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行概览接口（BE-034）。
 * 三个数据接口在响应头返回 X-Data-Updated-At（FE-031）；
 * /admin/overview/filters 为固定静态路由，先于数据接口装配。
 */
@RestController
public class OverviewController {

    private static final DateTimeFormatter HEADER_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/admin/overview/filters")
    public ResponseEntity<String> filters(HttpServletRequest request) {
        OverviewFilterOptions options = overviewService.filters(context(request),
                request.getParameter("alias_id"));
        return json(ManagementResponses.ok(options), null);
    }

    @GetMapping("/admin/overview/summary")
    public ResponseEntity<String> summary(HttpServletRequest request) {
        OverviewSummary summary = overviewService.summary(context(request), multiParams(request));
        return json(ManagementResponses.ok(summary), summary.dataUpdatedAt());
    }

    @GetMapping("/admin/overview/trends")
    public ResponseEntity<String> trends(HttpServletRequest request) {
        OverviewTrendResult trend = overviewService.trends(context(request), multiParams(request));
        return json(ManagementResponses.ok(trend), trend.dataUpdatedAt());
    }

    @GetMapping("/admin/overview/exceptions")
    public ResponseEntity<String> exceptions(HttpServletRequest request) {
        OverviewExceptionResult result =
                overviewService.exceptions(context(request), multiParams(request));
        return json(ManagementResponses.ok(result), result.dataUpdatedAt());
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
        return OverviewQueryParser.toMultiMap(request.getParameterMap());
    }

    private static ResponseEntity<String> json(String body, OffsetDateTime dataUpdatedAt) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON);
        if (dataUpdatedAt != null) {
            builder.header("X-Data-Updated-At", HEADER_TIME.format(dataUpdatedAt));
        }
        return builder.body(body);
    }
}
