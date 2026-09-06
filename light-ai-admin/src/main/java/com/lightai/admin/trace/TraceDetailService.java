package com.lightai.admin.trace;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.trace.TraceAttemptItem;
import com.lightai.client.trace.TraceDetail;
import com.lightai.client.trace.TraceRequestSummary;
import com.lightai.client.trace.TraceSubEntityItems.CapacityReservationItem;
import com.lightai.client.trace.TraceSubEntityItems.CircuitEventItem;
import com.lightai.client.trace.TraceSubEntityItems.QueueEntryItem;
import com.lightai.client.trace.TraceSubEntityItems.RecoveryDecisionItem;
import com.lightai.client.trace.TraceSubEntityItems.RouteDecisionItem;
import com.lightai.client.trace.TraceSummary;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.trace.JdbcObservationConfigReader.ObservationConfig;
import com.lightai.storage.trace.JdbcTraceDetailRepository;
import com.lightai.storage.trace.JdbcTraceRepository;
import com.lightai.storage.trace.ObservationRows;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Trace 详情装配（BE-032；BACKEND_PLAN 4.4.4.1）。
 * 先按 Trace.application 与宿主数据范围判权，再读取下级数据；
 * Credential ID/名称/掩码与 Provider Request ID、client_ip、诊断样本按字段权限
 * 在序列化前移除（C-012），服务端日志不记录移除前的响应对象。
 * include_diagnostics=true 需要诊断权限，且每次查看写审计。
 */
public class TraceDetailService {

    public static final String SAMPLE_DISABLED = "DISABLED";
    public static final String SAMPLE_NOT_SAMPLED = "NOT_SAMPLED";
    public static final String SAMPLE_AVAILABLE = "AVAILABLE";
    public static final String SAMPLE_EXPIRED = "EXPIRED";

    private static final int DEFAULT_TRACE_RETENTION_DAYS = 30;
    private static final String DEFAULT_TIMEZONE = "UTC";

    private final DataSource dataSource;
    private final JdbcTraceRepository traceRepository;
    private final JdbcTraceDetailRepository detailRepository;
    private final com.lightai.storage.trace.JdbcObservationConfigReader configReader;
    private final AuditService auditService;
    private final Clock clock;

    public TraceDetailService(DataSource dataSource, JdbcTraceRepository traceRepository,
                              JdbcTraceDetailRepository detailRepository,
                              com.lightai.storage.trace.JdbcObservationConfigReader configReader,
                              AuditService auditService, Clock clock) {
        this.dataSource = dataSource;
        this.traceRepository = traceRepository;
        this.detailRepository = detailRepository;
        this.configReader = configReader;
        this.auditService = auditService;
        this.clock = clock;
    }

    public TraceDetail detail(RequestContext context, String traceId, boolean includeDiagnostics) {
        RequestPermissions.require(context, Permissions.TRACE_VIEW);
        boolean credentialFields = RequestPermissions.has(context, Permissions.CREDENTIAL_VIEW);
        boolean diagnostics = RequestPermissions.has(context, Permissions.TRACE_DIAGNOSTICS);
        if (includeDiagnostics && !diagnostics) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "无诊断样本读取权限");
        }
        List<String> scope = TraceService.scopeApplications(context);
        try (var connection = dataSource.getConnection()) {
            ObservationRows.TraceRow row = traceRepository.findByTraceId(connection, traceId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                            "Trace不存在或已超出保留期"));
            if (!TraceService.inScope(row.application(), scope)) {
                throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权访问该应用数据");
            }
            ObservationConfig config = configReader.read(connection).orElse(null);
            OffsetDateTime now = OffsetDateTime.now(clock);

            List<ObservationRows.AttemptRow> attemptRows = traceRepository.listAttempts(connection, traceId);
            List<ObservationRows.RouteDecisionRow> routeRows =
                    detailRepository.routeDecisions(connection, traceId);
            List<ObservationRows.QueueEntryRow> queueRows =
                    detailRepository.queueEntries(connection, traceId);
            List<JdbcTraceDetailRepository.ReservationWithItems> reservationRows =
                    detailRepository.reservations(connection, traceId);
            List<ObservationRows.RecoveryDecisionRow> recoveryRows =
                    detailRepository.recoveryDecisions(connection, traceId);
            List<ObservationRows.CircuitEventRow> circuitRows =
                    detailRepository.circuitEvents(connection, traceId);

            ObservationRows.ContentSampleRow sample = null;
            if (includeDiagnostics) {
                sample = detailRepository.contentSample(connection, traceId).orElse(null);
            }
            String sampleStatus = sampleStatus(config, sample, now);
            if (includeDiagnostics && SAMPLE_AVAILABLE.equals(sampleStatus)) {
                auditDiagnosticsRead(connection, context, row);
            }

            Map<UUID, String> masks = credentialFields
                    ? detailRepository.maskedValuesByCredentialIds(connection,
                            attemptRows.stream().map(ObservationRows.AttemptRow::credentialId).toList())
                    : Map.of();

            return assemble(row, attemptRows, routeRows, queueRows, reservationRows,
                    recoveryRows, circuitRows, sample, sampleStatus, config,
                    credentialFields, diagnostics, includeDiagnostics, masks);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE,
                    "Trace详情当前无法读取");
        }
    }

    private String sampleStatus(ObservationConfig config, ObservationRows.ContentSampleRow sample,
                                OffsetDateTime now) {
        if (config == null || !config.diagnosticSamplingEnabled()) {
            return SAMPLE_DISABLED;
        }
        if (sample == null) {
            return SAMPLE_NOT_SAMPLED;
        }
        return sample.expiresAt() != null && sample.expiresAt().isAfter(now)
                ? SAMPLE_AVAILABLE : SAMPLE_EXPIRED;
    }

    /** 受控诊断读取审计：仅记录 ID 与动作，不记录样本内容。 */
    private void auditDiagnosticsRead(java.sql.Connection connection, RequestContext context,
                                      ObservationRows.TraceRow row) {
        try {
            auditService.recordSuccess(connection, new AuditRecord(
                    UUID.randomUUID(), context.requestId(), context.authContext().userId(),
                    "TRACE_DIAGNOSTICS_READ", "TRACE", row.traceId(),
                    AuditRecord.RESULT_SUCCEEDED, List.of(), null, null, row.sourceMode(),
                    context.sourceIpMasked()));
        } catch (Exception ignored) {
            // 审计失败不阻塞诊断读取，由审计失败告警路径处理
        }
    }

    private TraceDetail assemble(
            ObservationRows.TraceRow row,
            List<ObservationRows.AttemptRow> attemptRows,
            List<ObservationRows.RouteDecisionRow> routeRows,
            List<ObservationRows.QueueEntryRow> queueRows,
            List<JdbcTraceDetailRepository.ReservationWithItems> reservationRows,
            List<ObservationRows.RecoveryDecisionRow> recoveryRows,
            List<ObservationRows.CircuitEventRow> circuitRows,
            ObservationRows.ContentSampleRow sample,
            String sampleStatus,
            ObservationConfig config,
            boolean credentialFields,
            boolean diagnostics,
            boolean includeDiagnostics,
            Map<UUID, String> masks) {

        List<String> failedAttemptIds = attemptRows.stream()
                .filter(a -> "FAILED".equals(a.status()))
                .map(a -> a.id().toString()).toList();

        TraceSummary summary = new TraceSummary(
                row.traceId(), row.status(), row.startedAt(), row.endedAt(),
                row.totalMs() == null ? null : row.totalMs().longValue(),
                row.application(), row.project(), row.tenant(), row.tags(),
                row.aliasId() == null ? null : row.aliasId().toString(), row.alias(),
                row.configSnapshotNo(), row.sourceMode(), row.requestedStream(),
                row.responseCommitted(), row.finishReason(), row.attemptCount(), row.retryCount(),
                row.credentialFailoverCount(), row.fallbackCount(), row.queuedMs(),
                row.firstTokenMs() == null ? null : row.firstTokenMs().longValue(),
                row.inputTokens(), row.outputTokens(), row.totalTokens(),
                row.responseInputTokens(), row.responseOutputTokens(), row.responseTotalTokens(),
                row.usageSource(), row.inputCost(), row.outputCost(), row.totalCost(),
                row.currency(), row.errorCode(), row.errorSummary(),
                row.finalAttemptId() == null ? null : row.finalAttemptId().toString(),
                failedAttemptIds);

        TraceRequestSummary requestSummary = toRequestSummary(row, sample, sampleStatus,
                diagnostics, includeDiagnostics);

        List<TraceAttemptItem> attempts = new ArrayList<>(attemptRows.size());
        for (ObservationRows.AttemptRow attempt : attemptRows) {
            attempts.add(toAttemptItem(attempt, credentialFields, diagnostics,
                    masks.get(attempt.credentialId())));
        }
        List<RouteDecisionItem> routeDecisions = routeRows.stream().map(r -> new RouteDecisionItem(
                r.sequence(), idOrNull(r.routeCandidateId()), r.decision(), r.reasonCode(),
                r.reasonDetail(), r.observedStatus(), r.createdAt())).toList();
        List<QueueEntryItem> queueEntries = queueRows.stream().map(q -> new QueueEntryItem(
                q.sequence(), q.blockingPolicyIds(), q.estimatedTokens(), q.status(),
                q.enqueuedAt(), q.deadlineAt(), q.acquiredAt(), q.endedAt(), q.wakeReason(),
                q.errorCode())).toList();
        List<CapacityReservationItem> reservations = reservationRows.stream()
                .map(r -> new CapacityReservationItem(
                        r.reservation().id().toString(),
                        r.reservation().attemptId() == null ? null
                                : r.reservation().attemptId().toString(),
                        r.policyIds(), r.reservation().reservedTokens(),
                        r.reservation().actualTokens(), r.reservation().status(),
                        r.reservation().releaseReason(), r.reservation().createdAt(),
                        r.reservation().settledAt()))
                .toList();
        List<RecoveryDecisionItem> recoveries = recoveryRows.stream().map(r -> new RecoveryDecisionItem(
                r.sequence(), r.sourceAttemptId().toString(), r.action(), r.reasonCode(),
                r.scheduledDelayMs(), idOrNull(r.targetRouteCandidateId()),
                idOrNull(r.targetCredentialId()), r.retriesUsed(), r.credentialFailoversUsed(),
                r.fallbacksUsed(), r.remainingTimeoutMs(), r.createdAt())).toList();
        List<CircuitEventItem> circuitEvents = circuitRows.stream().map(c -> new CircuitEventItem(
                c.circuitId().toString(), c.fromState(), c.toState(), c.triggerType(),
                c.errorCode(), c.reason(), c.occurredAt())).toList();

        var timeline = TimelineBuilder.build(row, attemptRows, routeRows, queueRows,
                recoveryRows, circuitRows);

        int retentionDays = config == null ? DEFAULT_TRACE_RETENTION_DAYS : config.traceRetentionDays();
        OffsetDateTime detailExpiresAt = row.startedAt() == null ? null
                : row.startedAt().plusDays(retentionDays);

        return new TraceDetail(summary, requestSummary, attempts, routeDecisions, queueEntries,
                reservations, recoveries, circuitEvents, timeline, detailExpiresAt);
    }

    @SuppressWarnings("unchecked")
    private TraceRequestSummary toRequestSummary(ObservationRows.TraceRow row,
                                                 ObservationRows.ContentSampleRow sample,
                                                 String sampleStatus,
                                                 boolean diagnostics,
                                                 boolean includeDiagnostics) {
        Map<String, Object> request = row.requestSummary() == null
                ? Map.of() : row.requestSummary();
        boolean showSample = includeDiagnostics && SAMPLE_AVAILABLE.equals(sampleStatus);
        List<TraceRequestSummary.TraceSampledMessage> sampledMessages = List.of();
        String sampledResponse = null;
        if (showSample && sample != null) {
            sampledMessages = parseSampledMessages(sample.sampledMessagesJson());
            sampledResponse = sample.sampledResponse();
        }
        return new TraceRequestSummary(
                row.sourceMode(),
                row.accessCredentialName(),
                request.get("request_user") == null ? null : String.valueOf(request.get("request_user")),
                diagnostics ? row.clientIp() : null,
                row.userAgent(),
                row.configSnapshotNo(),
                longOf(request.get("message_count")),
                longOf(request.get("system_message_count")),
                longOf(request.get("user_message_count")),
                longOf(request.get("assistant_message_count")),
                longOf(request.get("input_char_count")),
                row.requestedStream(),
                decimalOf(request.get("temperature")),
                decimalOf(request.get("top_p")),
                longOf(request.get("max_tokens")),
                longOf(request.get("stop_count")),
                listOfStrings(request.get("provider_option_keys")),
                sampleStatus,
                sampledMessages,
                sampledResponse);
    }

    private static List<TraceRequestSummary.TraceSampledMessage> parseSampledMessages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = com.lightai.client.json.ProtocolJson.protocol()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            List<TraceRequestSummary.TraceSampledMessage> messages = new ArrayList<>();
            for (Map<String, Object> entry : raw) {
                messages.add(new TraceRequestSummary.TraceSampledMessage(
                        entry.get("role") == null ? null : String.valueOf(entry.get("role")),
                        entry.get("content") == null ? null : String.valueOf(entry.get("content"))));
            }
            return List.copyOf(messages);
        } catch (Exception e) {
            throw new IllegalStateException("诊断样本解析失败", e);
        }
    }

    private TraceAttemptItem toAttemptItem(ObservationRows.AttemptRow attempt,
                                           boolean credentialFields, boolean diagnostics,
                                           String maskedValue) {
        Map<String, Object> resolved = attempt.resolvedParameters() == null
                ? Map.of() : attempt.resolvedParameters();
        return new TraceAttemptItem(
                attempt.sequence(), attempt.attemptType(), attempt.status(),
                attempt.providerNameSnapshot(), attempt.providerModelNameSnapshot(),
                attempt.modelIdSnapshot(),
                credentialFields ? attempt.credentialNameSnapshot() : null,
                credentialFields ? maskedValue : null,
                attempt.startedAt(), attempt.providerStartedAt(), attempt.responseHeadersAt(),
                attempt.firstTokenAt(), attempt.endedAt(), attempt.dispatchMs(),
                attempt.responseHeaderMs(), attempt.firstTokenMs(), attempt.totalMs(),
                attempt.endpointHost(), attempt.httpStatus(),
                diagnostics ? attempt.providerRequestId() : null,
                attempt.responseCommitted(), attempt.finishReason(),
                attempt.errorCode(), attempt.errorCategory(), attempt.errorStage(),
                attempt.errorSummary(), attempt.retryable(), attempt.retryAfterMs(),
                resolved, attempt.inputTokens(), attempt.outputTokens(), attempt.totalTokens(),
                attempt.usageSource(), attempt.inputPrice(), attempt.outputPrice(),
                attempt.priceUnit(), attempt.currency(), attempt.inputCost(),
                attempt.outputCost(), attempt.totalCost());
    }

    private static String idOrNull(UUID id) {
        return id == null ? null : id.toString();
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimalOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object entry : list) {
            if (entry != null) {
                values.add(String.valueOf(entry));
            }
        }
        return List.copyOf(values);
    }
}
