package com.lightai.admin.calls;

import com.lightai.admin.trace.TraceDetailService;
import com.lightai.admin.trace.TraceService;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.calls.CallResults.CallDetail;
import com.lightai.client.calls.CallResults.CallListItem;
import com.lightai.client.paging.PageResult;
import com.lightai.client.trace.TraceDetail;
import com.lightai.client.trace.TraceListItem;
import java.util.List;
import java.util.Map;

/**
 * V2 调用记录查询（BE-231；PRD 9.7，BACKEND_PLAN /admin/calls）。
 * request_id 即既有 trace_id：同一存储、同一权限与数据范围、同一时间线装配，
 * 仅按 V2 契约重新暴露路径与字段命名；默认无正文，凭证与诊断字段在服务端裁剪。
 * 越权与筛选校验复用 TraceService，避免两套查询口径。
 */
public class CallObservationService {

    private final TraceService traceService;
    private final TraceDetailService traceDetailService;

    public CallObservationService(TraceService traceService, TraceDetailService traceDetailService) {
        this.traceService = traceService;
        this.traceDetailService = traceDetailService;
    }

    public PageResult<CallListItem> list(RequestContext context, Map<String, List<String>> params) {
        PageResult<TraceListItem> page = traceService.list(context, params);
        List<CallListItem> items = page.items().stream().map(CallObservationService::toCallItem).toList();
        return new PageResult<>(items, page.total(), page.page(), page.pageSize(), page.sort(),
                page.queryStartedAt(), page.dataUpdatedAt());
    }

    public CallDetail detail(RequestContext context, String requestId, boolean includeDiagnostics) {
        return toCallDetail(traceDetailService.detail(context, requestId, includeDiagnostics));
    }

    /** Trace 列表查询解析（V2 与过渡接口共用同一筛选与分页白名单）。 */
    static CallListItem toCallItem(TraceListItem item) {
        return new CallListItem(
                item.traceId(), item.startedAt(), item.application(), item.alias(),
                item.finalChannelName(), item.finalUpstreamModelName(), item.requestedStream(),
                item.status(), item.anomalousRunning(), item.attemptCount(), item.retryCount(),
                item.credentialFailoverCount(), item.fallbackCount(), item.queuedMs(),
                item.firstTokenMs(), item.totalMs(), item.usageSource(),
                item.inputTokens(), item.outputTokens(), item.totalTokens(),
                item.totalCost(), item.currency(), item.errorCode());
    }

    static CallDetail toCallDetail(TraceDetail detail) {
        var trace = detail.trace();
        // 详情摘要不含最终渠道列；最终渠道/上游模型取最后一次 Attempt 的快照（Attempt 按顺序排列）
        String finalChannel = null;
        String finalUpstream = null;
        if (!detail.attempts().isEmpty()) {
            var last = detail.attempts().get(detail.attempts().size() - 1);
            finalChannel = last.channelNameSnapshot();
            finalUpstream = last.upstreamModelNameSnapshot();
        }
        return new CallDetail(
                trace.traceId(), trace.status(), trace.startedAt(), trace.endedAt(),
                trace.totalMs(), trace.firstTokenMs(),
                trace.application(), trace.alias(),
                finalChannel, finalUpstream,
                trace.finishReason(), trace.errorCode(), trace.errorSummary(),
                trace.attemptCount(), trace.retryCount(), trace.credentialFailoverCount(),
                trace.fallbackCount(), trace.queuedMs(), trace.requestedStream(),
                trace.responseCommitted(), trace.configSnapshotNo(), trace.usageSource(),
                trace.inputTokens(), trace.outputTokens(), trace.totalTokens(),
                trace.inputCost(), trace.outputCost(), trace.totalCost(), trace.currency(),
                detail.requestSummary(), detail.attempts(), detail.routeDecisions(),
                detail.queueEntries(), detail.capacityReservations(), detail.recoveryDecisions(),
                detail.circuitEvents(), detail.timeline(), detail.detailExpiresAt());
    }
}
