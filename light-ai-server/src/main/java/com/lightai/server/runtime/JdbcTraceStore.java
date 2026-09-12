package com.lightai.server.runtime;

import com.lightai.admin.trace.TraceFinalizer;
import com.lightai.admin.usage.UsageAggregator;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.trace.TraceStore;
import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC 持久化 TraceStore（BE-031/033/036，DB-P03）：
 * 1. 真实写入 trace 与 attempt 表，供调用观测页面（Trace/TraceDetail/Usage/Overview）读取；
 * 2. 在 trace 最终化时调用 TraceFinalizer 插入唯一 UsageAggregationEvent；
 * 3. 触发 UsageAggregator 即时批量结算，使调用统计、分币种费用和大盘看板立即可见。
 */
public class JdbcTraceStore extends AbstractJdbcRepository implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcTraceStore.class);

    private final DataSource dataSource;
    private final ConfigSnapshotPort snapshotPort;
    private final TraceFinalizer traceFinalizer;
    private final UsageAggregator usageAggregator;
    private final Clock clock;
    private final Map<String, Boolean> committedFlags = new ConcurrentHashMap<>();

    public JdbcTraceStore(DataSource dataSource,
                          ConfigSnapshotPort snapshotPort,
                          TraceFinalizer traceFinalizer,
                          UsageAggregator usageAggregator,
                          Clock clock) {
        this.dataSource = dataSource;
        this.snapshotPort = snapshotPort;
        this.traceFinalizer = traceFinalizer;
        this.usageAggregator = usageAggregator;
        this.clock = clock;
    }

    @Override
    public TraceHandle create(String clientTraceIdOrNull, String model, String application) {
        if (clientTraceIdOrNull != null && !clientTraceIdOrNull.isBlank()) {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT 1 FROM " + qualify(conn, "trace") + " WHERE trace_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, clientTraceIdOrNull);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new LightAiException(ErrorCode.TRACE_ID_CONFLICT, "接入方提供的 trace_id 已被使用");
                        }
                    }
                }
            } catch (SQLException e) {
                throw translate("检查 Trace 冲突失败", e);
            }
        }

        String traceId = (clientTraceIdOrNull != null && !clientTraceIdOrNull.isBlank())
                ? clientTraceIdOrNull : UUID.randomUUID().toString();

        long snapshotNo = 0L;
        UUID aliasId = null;
        String currency = "USD";
        if (snapshotPort != null && snapshotPort.hasActiveSnapshot()) {
            try {
                var active = snapshotPort.active();
                snapshotNo = active.snapshotNo();
                var aliasOpt = active.alias(model);
                if (aliasOpt.isPresent()) {
                    var al = aliasOpt.get();
                    aliasId = parseUuidSafe(al.aliasId());
                    if (!al.candidates().isEmpty() && al.candidates().get(0).currency() != null) {
                        currency = al.candidates().get(0).currency();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime deadline = now.plusSeconds(120);
        String app = (application != null && !application.isBlank()) ? application : "default";

        try (Connection conn = dataSource.getConnection()) {
            DatabaseDialect d = dialect(conn);
            String sql = "INSERT INTO " + qualify(conn, "trace") + " ("
                    + "id, created_at, updated_at, trace_id, application, tags, source_mode, invocation_source, "
                    + "alias_id, alias, config_snapshot_no, requested_stream, response_committed, status, "
                    + "started_at, deadline_at, queued_ms, attempt_count, retry_count, credential_failover_count, "
                    + "fallback_count, input_tokens, output_tokens, total_tokens, response_input_tokens, "
                    + "response_output_tokens, response_total_tokens, input_cost, output_cost, total_cost, "
                    + "currency, retryable, request_summary, terminal_version"
                    + ") VALUES (?, " + d.nowFunction() + ", " + d.nowFunction() + ", ?, ?, " + d.jsonPlaceholder()
                    + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ?, 0, "
                    + d.jsonPlaceholder() + ", 0)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                d.bindUuid(ps, 1, id);
                ps.setString(2, traceId);
                ps.setString(3, app);
                d.bindJson(ps, 4, "{}");
                ps.setString(5, "STANDALONE_SERVER");
                ps.setString(6, "APPLICATION");
                d.bindUuid(ps, 7, aliasId);
                ps.setString(8, model);
                ps.setLong(9, snapshotNo);
                ps.setBoolean(10, false);
                ps.setBoolean(11, false);
                ps.setString(12, "RUNNING");
                ps.setObject(13, now);
                ps.setObject(14, deadline);
                ps.setString(15, currency);
                d.bindJson(ps, 16, "{}");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Duplicate") || msg.contains("23505") || e.getErrorCode() == 1062) {
                throw new LightAiException(ErrorCode.TRACE_ID_CONFLICT, "接入方提供的 trace_id 已被使用");
            }
            throw translate("创建 Trace 失败", e);
        }

        return new TraceHandle(traceId, snapshotNo);
    }

    @Override
    public String startAttempt(String traceId, String candidateId, String providerType, String modelId) {
        return startAttempt(traceId, new AttemptIdentity(
                parseUuidSafe(candidateId), null, null, null, providerType, modelId, null,
                null, null, 0, null));
    }

    @Override
    public String startAttempt(String traceId, AttemptIdentity identity) {
        UUID attemptUuid = UUID.randomUUID();
        int sequence = 1;

        try (Connection conn = dataSource.getConnection()) {
            DatabaseDialect d = dialect(conn);
            String countSql = "SELECT count(*) FROM " + qualify(conn, "attempt") + " WHERE trace_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                ps.setString(1, traceId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        sequence = rs.getInt(1) + 1;
                    }
                }
            }

            // BE-223：Attempt 固定单渠道/真实模型/单 Key 身份；缺失时退化为历史占位
            UUID routeCandidateUuid = identity.routeCandidateId();
            UUID channelUuid = identity.channelId() != null ? identity.channelId()
                    : (routeCandidateUuid != null ? routeCandidateUuid : UUID.randomUUID());
            UUID upstreamModelUuid = identity.upstreamModelId() != null ? identity.upstreamModelId()
                    : channelUuid;
            UUID credentialUuid = identity.channelCredentialId() != null ? identity.channelCredentialId()
                    : channelUuid;

            String pType = (identity.providerType() != null && !identity.providerType().isBlank())
                    ? identity.providerType() : "UNKNOWN";
            String mId = (identity.upstreamModelName() != null && !identity.upstreamModelName().isBlank())
                    ? identity.upstreamModelName() : "UNKNOWN";
            String attemptType = sequence == 1 ? "INITIAL" : "RETRY";
            OffsetDateTime now = OffsetDateTime.now(clock);
            BigDecimal inputPrice = identity.inputPrice() == null ? BigDecimal.ZERO : identity.inputPrice();
            BigDecimal outputPrice = identity.outputPrice() == null ? BigDecimal.ZERO : identity.outputPrice();
            int priceUnit = identity.priceUnit() > 0 ? identity.priceUnit() : 1000;
            String currency = (identity.currency() != null && !identity.currency().isBlank())
                    ? identity.currency() : "USD";

            String sql = "INSERT INTO " + qualify(conn, "attempt") + " ("
                    + "id, created_at, updated_at, trace_id, " + d.quoteColumn("sequence") + ", attempt_type, "
                    + "route_candidate_id, channel_id, upstream_model_id, channel_credential_id, "
                    + "channel_name_snapshot, upstream_model_name_snapshot, model_id_snapshot, channel_credential_name_snapshot, "
                    + "status, started_at, endpoint_host, response_committed, retryable, "
                    + "resolved_parameters, input_tokens, output_tokens, total_tokens, "
                    + "input_price, output_price, price_unit, currency, input_cost, output_cost, total_cost"
                    + ") VALUES (?, " + d.nowFunction() + ", " + d.nowFunction() + ", ?, ?, ?, "
                    + "?, ?, ?, ?, ?, "
                    + "?, ?, ?, "
                    + "'RUNNING', ?, 'api.provider', 0, 0, "
                    + d.jsonPlaceholder() + ", 0, 0, 0, "
                    + "?, ?, ?, ?, 0, 0, 0)";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                d.bindUuid(ps, 1, attemptUuid);
                ps.setString(2, traceId);
                ps.setInt(3, sequence);
                ps.setString(4, attemptType);
                d.bindUuid(ps, 5, routeCandidateUuid);
                d.bindUuid(ps, 6, channelUuid);
                d.bindUuid(ps, 7, upstreamModelUuid);
                d.bindUuid(ps, 8, credentialUuid);
                ps.setString(9, pType);
                ps.setString(10, mId);
                ps.setString(11, mId);
                // Key 只落掩码快照，不落原文或完整 secret 引用
                ps.setString(12, identity.credentialMask() != null ? identity.credentialMask() : "default");
                ps.setObject(13, now);
                d.bindJson(ps, 14, "{}");
                ps.setBigDecimal(15, inputPrice);
                ps.setBigDecimal(16, outputPrice);
                ps.setInt(17, priceUnit);
                ps.setString(18, currency);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw translate("创建 Attempt 失败", e);
        }

        return attemptUuid.toString();
    }

    @Override
    public void finishAttempt(String traceId, String attemptId, String status, String errorCode,
                              long inputTokens, long outputTokens, String usageSource,
                              String costAmount, String costCurrency, boolean costEstimated) {
        finishAttempt(traceId, attemptId, status, errorCode, inputTokens, outputTokens, usageSource,
                null, null,
                costAmount == null || costAmount.isBlank() ? BigDecimal.ZERO : new BigDecimal(costAmount),
                costCurrency, costEstimated);
    }

    @Override
    public void finishAttempt(String traceId, String attemptId, String status, String errorCode,
                              long inputTokens, long outputTokens, String usageSource,
                              BigDecimal inputCost, BigDecimal outputCost, BigDecimal totalCost,
                              String costCurrency, boolean costEstimated) {
        UUID attemptUuid = parseUuidSafe(attemptId);
        if (attemptUuid == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        BigDecimal input = inputCost == null ? BigDecimal.ZERO : inputCost.max(BigDecimal.ZERO);
        BigDecimal output = outputCost == null ? BigDecimal.ZERO : outputCost.max(BigDecimal.ZERO);
        BigDecimal total = totalCost == null ? input.add(output) : totalCost.max(BigDecimal.ZERO);
        String currency = (costCurrency != null && !costCurrency.isBlank()) ? costCurrency : "USD";

        try (Connection conn = dataSource.getConnection()) {
            DatabaseDialect d = dialect(conn);
            String checkSql = "SELECT status, started_at FROM " + qualify(conn, "attempt")
                    + " WHERE trace_id = ? AND id = ?";
            OffsetDateTime startedAt = now;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, traceId);
                d.bindUuid(ps, 2, attemptUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LightAiException(ErrorCode.INTERNAL_ERROR, "Attempt 不存在");
                    }
                    String currentStatus = rs.getString("status");
                    if (!"RUNNING".equals(currentStatus)) {
                        throw new LightAiException(ErrorCode.INTERNAL_ERROR, "已结束的 Attempt 不允许状态回退");
                    }
                    OffsetDateTime st = d.readOffsetDateTime(rs, "started_at");
                    if (st != null) {
                        startedAt = st;
                    }
                }
            }

            long totalMs = Math.max(0, Duration.between(startedAt, now).toMillis());
            long totalTokens = inputTokens + outputTokens;

            String updateSql = "UPDATE " + qualify(conn, "attempt") + " SET "
                    + "status = ?, error_code = ?, input_tokens = ?, output_tokens = ?, total_tokens = ?, "
                    + "usage_source = ?, currency = ?, input_cost = ?, output_cost = ?, total_cost = ?, "
                    + "ended_at = ?, total_ms = ?, settled_at = ?, "
                    + "updated_at = " + d.nowFunction() + " "
                    + "WHERE trace_id = ? AND id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, status);
                ps.setString(2, errorCode);
                ps.setLong(3, inputTokens);
                ps.setLong(4, outputTokens);
                ps.setLong(5, totalTokens);
                ps.setString(6, usageSource != null ? usageSource : "ACTUAL");
                ps.setString(7, currency);
                ps.setBigDecimal(8, input);
                ps.setBigDecimal(9, output);
                ps.setBigDecimal(10, total);
                ps.setObject(11, now);
                ps.setLong(12, totalMs);
                ps.setObject(13, now);
                ps.setString(14, traceId);
                d.bindUuid(ps, 15, attemptUuid);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw translate("更新 Attempt 失败", e);
        }
    }

    @Override
    public void markCommitted(String traceId) {
        committedFlags.put(traceId, true);
        try (Connection conn = dataSource.getConnection()) {
            DatabaseDialect d = dialect(conn);
            String sql = "UPDATE " + qualify(conn, "trace")
                    + " SET response_committed = 1, updated_at = " + d.nowFunction()
                    + " WHERE trace_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, traceId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("标记 Trace 已提交失败: traceId={}, exception={}",
                    traceId, e.getClass().getSimpleName());
        }
    }

    @Override
    public boolean committed(String traceId) {
        if (Boolean.TRUE.equals(committedFlags.get(traceId))) {
            return true;
        }
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT response_committed FROM " + qualify(conn, "trace") + " WHERE trace_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, traceId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        boolean c = rs.getBoolean(1);
                        if (c) {
                            committedFlags.put(traceId, true);
                        }
                        return c;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("读取 response_committed 异常 exception={}", e.getClass().getSimpleName());
        }
        return false;
    }

    @Override
    public void finalizeTrace(String traceId, String status) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        try (Connection conn = dataSource.getConnection()) {
            DatabaseDialect d = dialect(conn);
            String checkSql = "SELECT status, started_at, currency FROM " + qualify(conn, "trace")
                    + " WHERE trace_id = ?";
            OffsetDateTime startedAt = now;
            String traceCurrency = "USD";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, traceId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LightAiException(ErrorCode.INTERNAL_ERROR, "Trace 不存在");
                    }
                    String currentStatus = rs.getString("status");
                    if (!"RUNNING".equals(currentStatus) && !"QUEUED".equals(currentStatus)) {
                        throw new LightAiException(ErrorCode.INTERNAL_ERROR, "已结束的 Trace 不允许回退到运行状态");
                    }
                    OffsetDateTime st = d.readOffsetDateTime(rs, "started_at");
                    if (st != null) {
                        startedAt = st;
                    }
                    String cur = rs.getString("currency");
                    if (cur != null && !cur.isBlank()) {
                        traceCurrency = cur;
                    }
                }
            }

            long totalMs = Math.max(0, Duration.between(startedAt, now).toMillis());

            // 统计全部 attempt 的 tokens 与 cost
            int attemptCount = 0;
            long inTokens = 0;
            long outTokens = 0;
            long totTokens = 0;
            long respInTokens = 0;
            long respOutTokens = 0;
            long respTotTokens = 0;
            BigDecimal totalCost = BigDecimal.ZERO;
            String usageSource = null;
            UUID finalAttemptId = null;
            UUID finalChannelId = null;
            UUID finalUpstreamModelId = null;
            UUID finalChannelCredentialId = null;
            String finalChannelName = null;
            String finalUpstreamModelName = null;

            String attemptsSql = "SELECT id, channel_id, upstream_model_id, channel_credential_id, "
                    + "channel_name_snapshot, upstream_model_name_snapshot, status, "
                    + "input_tokens, output_tokens, total_tokens, total_cost, usage_source, currency "
                    + "FROM " + qualify(conn, "attempt")
                    + " WHERE trace_id = ? ORDER BY " + d.quoteColumn("sequence") + " ASC";

            try (PreparedStatement ps = conn.prepareStatement(attemptsSql)) {
                ps.setString(1, traceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        attemptCount++;
                        finalAttemptId = d.readUuid(rs, "id");
                        finalChannelId = d.readUuid(rs, "channel_id");
                        finalUpstreamModelId = d.readUuid(rs, "upstream_model_id");
                        finalChannelCredentialId = d.readUuid(rs, "channel_credential_id");
                        finalChannelName = rs.getString("channel_name_snapshot");
                        finalUpstreamModelName = rs.getString("upstream_model_name_snapshot");
                        long aIn = rs.getLong("input_tokens");
                        long aOut = rs.getLong("output_tokens");
                        long aTot = rs.getLong("total_tokens");
                        BigDecimal aCost = rs.getBigDecimal("total_cost");
                        if (aCost != null) {
                            totalCost = totalCost.add(aCost);
                        }
                        inTokens += aIn;
                        outTokens += aOut;
                        totTokens += aTot;
                        usageSource = rs.getString("usage_source");
                        String aCur = rs.getString("currency");
                        if (aCur != null && !aCur.isBlank()) {
                            traceCurrency = aCur;
                        }
                        String aStatus = rs.getString("status");
                        if ("SUCCEEDED".equals(aStatus)) {
                            respInTokens = aIn;
                            respOutTokens = aOut;
                            respTotTokens = aTot;
                        }
                    }
                }
            }

            if ("SUCCEEDED".equals(status) && respTotTokens == 0) {
                respInTokens = inTokens;
                respOutTokens = outTokens;
                respTotTokens = totTokens;
            }

            String updateTraceSql = "UPDATE " + qualify(conn, "trace") + " SET "
                    + "status = ?, ended_at = ?, total_ms = ?, attempt_count = ?, "
                    + "input_tokens = ?, output_tokens = ?, total_tokens = ?, "
                    + "response_input_tokens = ?, response_output_tokens = ?, response_total_tokens = ?, "
                    + "total_cost = ?, currency = ?, usage_source = ?, "
                    + "final_attempt_id = ?, final_channel_id = ?, final_upstream_model_id = ?, final_channel_credential_id = ?, "
                    + "final_channel_name = ?, final_upstream_model_name = ?, "
                    + "updated_at = " + d.nowFunction() + " "
                    + "WHERE trace_id = ?";

            try (PreparedStatement ps = conn.prepareStatement(updateTraceSql)) {
                ps.setString(1, status);
                ps.setObject(2, now);
                ps.setLong(3, totalMs);
                ps.setInt(4, attemptCount);
                ps.setLong(5, inTokens);
                ps.setLong(6, outTokens);
                ps.setLong(7, totTokens);
                ps.setLong(8, respInTokens);
                ps.setLong(9, respOutTokens);
                ps.setLong(10, respTotTokens);
                ps.setBigDecimal(11, totalCost);
                ps.setString(12, traceCurrency);
                ps.setString(13, usageSource != null ? usageSource : "ACTUAL");
                d.bindUuid(ps, 14, finalAttemptId);
                d.bindUuid(ps, 15, finalChannelId);
                d.bindUuid(ps, 16, finalUpstreamModelId);
                d.bindUuid(ps, 17, finalChannelCredentialId);
                ps.setString(18, finalChannelName);
                ps.setString(19, finalUpstreamModelName);
                ps.setString(20, traceId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw translate("最终化 Trace 失败", e);
        }

        // 最终化事件写入与批量聚合
        if (traceFinalizer != null) {
            try {
                traceFinalizer.finalizeTrace(traceId);
            } catch (Exception e) {
                log.warn("TraceFinalizer 最终化异常: traceId={}, exception={}",
                        traceId, e.getClass().getSimpleName());
            }
        }
        if (usageAggregator != null) {
            try {
                usageAggregator.processPending(50);
            } catch (Exception e) {
                log.warn("UsageAggregator 聚合异常: traceId={}, exception={}",
                        traceId, e.getClass().getSimpleName());
            }
        }
    }

    @Override
    public List<AttemptView> attempts(String traceId) {
        List<AttemptView> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseDialect d = dialect(conn);
            String sql = "SELECT id, status, error_code, route_candidate_id FROM " + qualify(conn, "attempt")
                    + " WHERE trace_id = ? ORDER BY " + d.quoteColumn("sequence") + " ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, traceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = d.readUuid(rs, "id");
                        String status = rs.getString("status");
                        String errorCode = rs.getString("error_code");
                        UUID candidateId = d.readUuid(rs, "route_candidate_id");
                        list.add(new AttemptView(
                                id != null ? id.toString() : "",
                                status,
                                errorCode,
                                candidateId != null ? candidateId.toString() : null
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("读取 attempts 列表异常 exception={}", e.getClass().getSimpleName());
        }
        return List.copyOf(list);
    }

    @Override
    public int finalizeExpired(java.time.Instant now, String errorCode) {
        List<String> expired = new ArrayList<>();
        OffsetDateTime cutoff = now.atOffset(java.time.ZoneOffset.UTC);
        try (Connection conn = dataSource.getConnection()) {
            DatabaseDialect d = dialect(conn);
            String sql = "SELECT trace_id FROM " + qualify(conn, "trace")
                    + " WHERE status IN ('RUNNING','QUEUED') AND deadline_at IS NOT NULL AND deadline_at < ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        expired.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("扫描过期 Trace 失败 exception={}", e.getClass().getSimpleName());
            return 0;
        }
        int converged = 0;
        for (String traceId : expired) {
            try (Connection conn = dataSource.getConnection()) {
                DatabaseDialect d = dialect(conn);
                // 崩溃前 RUNNING 的 Attempt 补终态；已终态 Attempt 不回退
                String attemptSql = "UPDATE " + qualify(conn, "attempt") + " SET "
                        + "status = 'FAILED', error_code = ?, ended_at = ?, "
                        + "updated_at = " + d.nowFunction() + " "
                        + "WHERE trace_id = ? AND status = 'RUNNING'";
                try (PreparedStatement ps = conn.prepareStatement(attemptSql)) {
                    ps.setString(1, errorCode);
                    ps.setObject(2, cutoff);
                    ps.setString(3, traceId);
                    ps.executeUpdate();
                }
                String traceSql = "UPDATE " + qualify(conn, "trace") + " SET "
                        + "error_code = ?, retryable = 0, updated_at = " + d.nowFunction() + " "
                        + "WHERE trace_id = ? AND status IN ('RUNNING','QUEUED')";
                try (PreparedStatement ps = conn.prepareStatement(traceSql)) {
                    ps.setString(1, errorCode);
                    ps.setString(2, traceId);
                    ps.executeUpdate();
                }
                finalizeTrace(traceId, "FAILED");
                converged++;
            } catch (RuntimeException | SQLException raceFailure) {
                // 其他实例或线程已收敛该 Trace；保持唯一终态
                log.debug("过期 Trace 收敛跳过 trace_id={}, reason={}",
                        traceId, raceFailure.getClass().getSimpleName());
            }
        }
        if (converged > 0) {
            log.info("过期 Trace 收敛完成 count={}, code={}", converged, errorCode);
        }
        return converged;
    }

    private static UUID parseUuidSafe(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(str.strip());
        } catch (Exception e) {
            return null;
        }
    }
}
