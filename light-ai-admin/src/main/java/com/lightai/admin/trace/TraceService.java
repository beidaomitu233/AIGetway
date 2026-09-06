package com.lightai.admin.trace;

import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.trace.TraceListQueryParser.TraceListQuery;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.paging.PageResult;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.trace.TraceListItem;
import com.lightai.storage.trace.JdbcTraceRepository;
import com.lightai.storage.trace.ObservationRows.TraceRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Trace 列表组合查询（BE-031；BACKEND_PLAN 4.4.4.1）。
 * 权限与数据范围先于查询注入；精确 trace_id 分支忽略业务筛选与分页，
 * 越权或不存在一律空列表，不泄露 ID 是否存在于其他应用。
 * RUNNING/QUEUED 的 total_ms 在响应层按当前时间计算，不回写数据库（FE-029）。
 */
public class TraceService {

    /** 异常运行判定：超过总截止加清理宽限（与预占 expires_at 的 30 秒宽限一致）。 */
    static final long ANOMALOUS_GRACE_SECONDS = 30;

    private final DataSource dataSource;
    private final JdbcTraceRepository traceRepository;
    private final PageResultFactory pageResultFactory;
    private final Clock clock;

    public TraceService(DataSource dataSource, JdbcTraceRepository traceRepository,
                        PageResultFactory pageResultFactory, Clock clock) {
        this.dataSource = dataSource;
        this.traceRepository = traceRepository;
        this.pageResultFactory = pageResultFactory;
        this.clock = clock;
    }

    public PageResult<TraceListItem> list(RequestContext context, Map<String, List<String>> params) {
        RequestPermissions.require(context, Permissions.TRACE_VIEW);
        boolean credentialFields = RequestPermissions.has(context, Permissions.CREDENTIAL_VIEW);
        boolean diagnostics = RequestPermissions.has(context, Permissions.TRACE_DIAGNOSTICS);

        TraceListQuery query = TraceListQueryParser.parse(params);
        if (!query.accessCredentialIds().isEmpty() && !credentialFields) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权使用 access_credential_id 筛选");
        }
        if (!query.credentialIds().isEmpty() && !credentialFields) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权使用 credential_id 筛选");
        }
        if (query.clientIp() != null && !diagnostics) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权使用 client_ip 筛选");
        }

        JdbcTraceRepository.TraceFilter filter = toFilter(query, scopeApplications(context));
        try (Connection connection = dataSource.getConnection()) {
            if (query.exactId()) {
                return exactPage(connection, context, query);
            }
            List<TraceRow> rows = traceRepository.list(connection, filter, query.page().sort(),
                    query.page().limit(), query.page().offset());
            long total = traceRepository.count(connection, filter);
            List<TraceListItem> items = rows.stream().map(row -> toItem(row, now())).toList();
            OffsetDateTime dataUpdatedAt = rows.stream().map(TraceRow::updatedAt)
                    .max(OffsetDateTime::compareTo).orElse(null);
            return pageResultFactory.create(items, total, query.page(), dataUpdatedAt);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "调用观测数据当前无法读取");
        }
    }

    private PageResult<TraceListItem> exactPage(Connection connection, RequestContext context,
                                                TraceListQuery query) {
        List<String> scope = scopeApplications(context);
        TraceRow row = traceRepository.findByTraceId(connection, query.exactTraceId()).orElse(null);
        if (row != null && inScope(row.application(), scope)) {
            return pageResultFactory.create(List.of(toItem(row, now())), 1, 1,
                    query.page().pageSize(), query.page().sort(), row.updatedAt());
        }
        return pageResultFactory.create(List.of(), 0, 1, query.page().pageSize(),
                query.page().sort(), null);
    }

    static boolean inScope(String application, List<String> scopeApplications) {
        return scopeApplications.isEmpty() || scopeApplications.contains(application);
    }

    static List<String> scopeApplications(RequestContext context) {
        return context == null || context.authContext() == null
                ? List.of() : context.authContext().applicationScope();
    }

    static JdbcTraceRepository.TraceFilter toFilter(TraceListQuery query,
                                                    List<String> scopeApplications) {
        return new JdbcTraceRepository.TraceFilter(
                query.exactTraceId(), query.startAt(), query.endAt(),
                query.applications(), scopeApplications, query.aliasIds(), query.providerIds(),
                query.providerModelIds(), query.statuses(), query.projects(), query.tenants(),
                query.tagKey(), query.tagValue(), query.sourceModes(), query.accessCredentialIds(),
                query.credentialIds(), query.requestUser(), query.clientIp(), query.attemptTypes(),
                query.errorCodes(), query.requestedStream(), query.usageSources(),
                query.hasRetry(), query.hasCredentialFailover(), query.hasFallback(),
                query.minTotalMs(), query.maxTotalMs(), query.anomalousRunning());
    }

    private TraceListItem toItem(TraceRow row, OffsetDateTime now) {
        boolean anomalous = "RUNNING".equals(row.status()) && row.deadlineAt() != null
                && row.deadlineAt().isBefore(now.minusSeconds(ANOMALOUS_GRACE_SECONDS));
        Long totalMs = row.totalMs() == null ? null : row.totalMs().longValue();
        if (totalMs == null && ("RUNNING".equals(row.status()) || "QUEUED".equals(row.status()))) {
            totalMs = Math.max(0, Duration.between(row.startedAt(), now).toMillis());
        }
        BigDecimal totalCost = row.totalCost();
        boolean noCost = row.inputTokens() == 0 && row.outputTokens() == 0;
        return new TraceListItem(
                row.traceId(), row.startedAt(), row.sourceMode(), row.accessCredentialName(),
                row.application(), row.project(), row.tenant(), row.alias(),
                row.finalProviderName(), row.finalProviderModelName(), row.requestedStream(),
                row.status(), anomalous, row.attemptCount(), row.retryCount(),
                row.credentialFailoverCount(), row.fallbackCount(), row.queuedMs(),
                row.firstTokenMs() == null ? null : row.firstTokenMs().longValue(),
                totalMs, row.usageSource(), row.totalTokens(),
                noCost ? null : totalCost, row.currency(), row.errorCode());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
