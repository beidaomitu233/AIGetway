package com.lightai.storage.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lightai.client.json.ProtocolJson;
import com.lightai.storage.trace.ObservationRows.TraceRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * trace JDBC 仓储（DATABASE_PLAN 第 15 表，BE-031/036）。
 * R 类运行事实无软删除；排序表达由服务层白名单生成；
 * 精确 trace_id 与普通组合查询共用同一筛选构造，权限范围由服务层先注入。
 */
public class JdbcTraceRepository {

    private static final String COLUMNS =
            "id, trace_id, application, project, tenant, tags, source_mode, invocation_source, "
                    + "alias_id, alias, config_snapshot_no, requested_stream, response_committed, status, "
                    + "started_at, deadline_at, ended_at, total_ms, first_token_ms, queued_ms, "
                    + "attempt_count, retry_count, credential_failover_count, fallback_count, "
                    + "final_attempt_id, final_provider_id, final_provider_model_id, final_credential_id, "
                    + "access_credential_id, final_provider_name, final_provider_model_name, "
                    + "access_credential_name, input_tokens, output_tokens, total_tokens, "
                    + "response_input_tokens, response_output_tokens, response_total_tokens, usage_source, "
                    + "input_cost, output_cost, total_cost, currency, finish_reason, "
                    + "error_code, error_category, error_stage, error_summary, retryable, "
                    + "request_summary, client_ip, user_agent, updated_at";

    /** 观测筛选项；列表多值均已由服务层校验个数与枚举。 */
    public record TraceFilter(
            String exactTraceId,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            List<String> applications,
            List<String> scopeApplications,
            List<String> aliasIds,
            List<String> providerIds,
            List<String> providerModelIds,
            List<String> statuses,
            List<String> projects,
            List<String> tenants,
            String tagKey,
            String tagValue,
            List<String> sourceModes,
            List<String> accessCredentialIds,
            List<String> credentialIds,
            String requestUser,
            String clientIp,
            List<String> attemptTypes,
            List<String> errorCodes,
            Boolean requestedStream,
            List<String> usageSources,
            Boolean hasRetry,
            Boolean hasCredentialFailover,
            Boolean hasFallback,
            Long minTotalMs,
            Long maxTotalMs,
            Boolean anomalousRunning) {

        /** 是否为精确 trace_id 分支：忽略业务筛选与分页，仍执行权限校验。 */
        public boolean exactId() {
            return exactTraceId != null && !exactTraceId.isBlank();
        }
    }

    private final String schemaName;

    public JdbcTraceRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcTraceRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public Optional<TraceRow> findByTraceId(Connection connection, String traceId) {
        String sql = "SELECT " + COLUMNS + " FROM " + qualified() + " WHERE trace_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw translate("Trace读取失败", e);
        }
    }

    public List<TraceRow> list(Connection connection, TraceFilter filter, String sortExpression,
                               int limit, long offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        sql.append(" ORDER BY ").append(sortExpression).append(", trace_id ASC LIMIT ? OFFSET ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, params);
            statement.setInt(params.size() + 1, limit);
            statement.setLong(params.size() + 2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                List<TraceRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapRow(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("Trace列表查询失败", e);
        }
    }

    public long count(Connection connection, TraceFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualified())
                .append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("Trace计数失败", e);
        }
    }

    /**
     * 导出游标：逐行消费，不把完整结果载入内存；
     * fetchSize 交给驱动按游标取数，连接断开时由调用方关闭连接中止游标。
     */
    public void forEachRow(Connection connection, TraceFilter filter, String sortExpression,
                           int fetchSize, Consumer<TraceRow> consumer) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ")
                .append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        sql.append(" ORDER BY ").append(sortExpression).append(", trace_id ASC");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString(),
                java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(fetchSize);
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    consumer.accept(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw translate("Trace导出查询失败", e);
        }
    }

    /** 终态 Trace 及其 Attempt 一次性读出，供最终化与聚合贡献计算（BE-033）。 */
    public TerminalTrace findTerminalWithAttempts(Connection connection, String traceId) {
        TraceRow trace = findByTraceId(connection, traceId)
                .orElseThrow(() -> new IllegalStateException("待聚合Trace不存在：" + traceId));
        return new TerminalTrace(trace, listAttempts(connection, traceId));
    }

    /** Attempt 按 sequence 升序；详情与聚合共用同一读取。 */
    public List<ObservationRows.AttemptRow> listAttempts(Connection connection, String traceId) {
        List<ObservationRows.AttemptRow> attempts = new ArrayList<>();
        String sql = "SELECT " + ATTEMPT_COLUMNS + " FROM " + schemaName + ".attempt "
                + "WHERE trace_id = ? ORDER BY sequence ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    attempts.add(mapAttempt(rs));
                }
            }
        } catch (SQLException e) {
            throw translate("TraceAttempt读取失败", e);
        }
        return List.copyOf(attempts);
    }

    public record TerminalTrace(TraceRow trace, List<ObservationRows.AttemptRow> attempts) {
    }

    private void appendFilter(StringBuilder sql, List<Object> params, TraceFilter filter) {
        if (filter == null) {
            return;
        }
        if (filter.exactId()) {
            sql.append(" AND trace_id = ?");
            params.add(filter.exactTraceId());
            return;
        }
        if (filter.startAt() != null) {
            sql.append(" AND started_at >= ?");
            params.add(filter.startAt());
        }
        if (filter.endAt() != null) {
            sql.append(" AND started_at < ?");
            params.add(filter.endAt());
        }
        appendIn(sql, params, "application", filter.applications());
        appendIn(sql, params, "application", filter.scopeApplications());
        appendIn(sql, params, "alias_id", filter.aliasIds());
        appendIn(sql, params, "final_provider_id", filter.providerIds());
        appendIn(sql, params, "final_provider_model_id", filter.providerModelIds());
        appendIn(sql, params, "status", filter.statuses());
        appendIn(sql, params, "project", filter.projects());
        appendIn(sql, params, "tenant", filter.tenants());
        appendIn(sql, params, "source_mode", filter.sourceModes());
        appendIn(sql, params, "access_credential_id", filter.accessCredentialIds());
        appendIn(sql, params, "final_credential_id", filter.credentialIds());
        if (filter.tagKey() != null && !filter.tagKey().isBlank()) {
            sql.append(" AND tags ->> ? = ?");
            params.add(filter.tagKey().strip());
            params.add(filter.tagValue() == null ? "" : filter.tagValue().strip());
        }
        if (filter.requestUser() != null && !filter.requestUser().isBlank()) {
            sql.append(" AND request_user = ?");
            params.add(filter.requestUser().strip());
        }
        if (filter.clientIp() != null && !filter.clientIp().isBlank()) {
            sql.append(" AND host(client_ip) = ?");
            params.add(filter.clientIp().strip());
        }
        if (filter.attemptTypes() != null && !filter.attemptTypes().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM ").append(schemaName)
                    .append(".attempt a WHERE a.trace_id = trace.trace_id AND a.attempt_type IN ")
                    .append(placeholders(filter.attemptTypes().size())).append(")");
            params.addAll(filter.attemptTypes());
        }
        appendIn(sql, params, "error_code", filter.errorCodes());
        if (filter.requestedStream() != null) {
            sql.append(" AND requested_stream = ?");
            params.add(filter.requestedStream());
        }
        appendIn(sql, params, "usage_source", filter.usageSources());
        appendCountCompare(sql, params, "retry_count", filter.hasRetry());
        appendCountCompare(sql, params, "credential_failover_count", filter.hasCredentialFailover());
        appendCountCompare(sql, params, "fallback_count", filter.hasFallback());
        if (filter.minTotalMs() != null) {
            sql.append(" AND total_ms >= ?");
            params.add(filter.minTotalMs());
        }
        if (filter.maxTotalMs() != null) {
            sql.append(" AND total_ms <= ?");
            params.add(filter.maxTotalMs());
        }
        if (filter.anomalousRunning() != null) {
            String anomalous = "status = 'RUNNING' AND deadline_at < now() - interval '30 seconds'";
            sql.append(filter.anomalousRunning() ? " AND " : " AND NOT (").append(anomalous);
            if (!filter.anomalousRunning()) {
                sql.append(")");
            }
        }
    }

    private static void appendCountCompare(StringBuilder sql, List<Object> params,
                                           String column, Boolean positive) {
        if (positive == null) {
            return;
        }
        sql.append(" AND ").append(column).append(positive ? " > 0" : " = 0");
    }

    private static void appendIn(StringBuilder sql, List<Object> params, String column,
                                 List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN ").append(placeholders(values.size()));
        params.addAll(values);
    }

    private static String placeholders(int count) {
        return "(" + String.join(", ", java.util.Collections.nCopies(count, "?")) + ")";
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    private String qualified() {
        return schemaName + ".trace";
    }

    private static final String ATTEMPT_COLUMNS =
            "id, trace_id, sequence, attempt_type, route_candidate_id, provider_id, provider_model_id, "
                    + "credential_pool_id, credential_id, provider_name_snapshot, "
                    + "provider_model_name_snapshot, model_id_snapshot, credential_name_snapshot, status, "
                    + "started_at, provider_started_at, response_headers_at, first_token_at, ended_at, "
                    + "dispatch_ms, response_header_ms, first_token_ms, total_ms, endpoint_host, "
                    + "http_status, provider_request_id, response_committed, finish_reason, "
                    + "error_code, error_category, error_stage, error_summary, retryable, retry_after_ms, "
                    + "resolved_parameters, input_tokens, output_tokens, total_tokens, usage_source, "
                    + "input_price, output_price, price_unit, currency, input_cost, output_cost, "
                    + "total_cost, settled_at";

    private TraceRow mapRow(ResultSet rs) throws SQLException {
        return new TraceRow(
                rs.getObject("id", UUID.class),
                rs.getString("trace_id"),
                rs.getString("application"),
                rs.getString("project"),
                rs.getString("tenant"),
                fromJsonMap(rs.getString("tags")),
                rs.getString("source_mode"),
                rs.getString("invocation_source"),
                rs.getObject("alias_id", UUID.class),
                rs.getString("alias"),
                rs.getLong("config_snapshot_no"),
                rs.getBoolean("requested_stream"),
                rs.getBoolean("response_committed"),
                rs.getString("status"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("deadline_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                (Integer) rs.getObject("total_ms"),
                (Integer) rs.getObject("first_token_ms"),
                rs.getLong("queued_ms"),
                rs.getInt("attempt_count"),
                rs.getInt("retry_count"),
                rs.getInt("credential_failover_count"),
                rs.getInt("fallback_count"),
                rs.getObject("final_attempt_id", UUID.class),
                rs.getObject("final_provider_id", UUID.class),
                rs.getObject("final_provider_model_id", UUID.class),
                rs.getObject("final_credential_id", UUID.class),
                rs.getObject("access_credential_id", UUID.class),
                rs.getString("final_provider_name"),
                rs.getString("final_provider_model_name"),
                rs.getString("access_credential_name"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("total_tokens"),
                rs.getLong("response_input_tokens"),
                rs.getLong("response_output_tokens"),
                rs.getLong("response_total_tokens"),
                rs.getString("usage_source"),
                rs.getBigDecimal("input_cost"),
                rs.getBigDecimal("output_cost"),
                rs.getBigDecimal("total_cost"),
                rs.getString("currency"),
                rs.getString("finish_reason"),
                rs.getString("error_code"),
                rs.getString("error_category"),
                rs.getString("error_stage"),
                rs.getString("error_summary"),
                rs.getBoolean("retryable"),
                fromJsonMapObject(rs.getString("request_summary")),
                rs.getString("client_ip"),
                rs.getString("user_agent"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ObservationRows.AttemptRow mapAttempt(ResultSet rs) throws SQLException {
        return new ObservationRows.AttemptRow(
                rs.getObject("id", UUID.class),
                rs.getString("trace_id"),
                rs.getInt("sequence"),
                rs.getString("attempt_type"),
                rs.getObject("route_candidate_id", UUID.class),
                rs.getObject("provider_id", UUID.class),
                rs.getObject("provider_model_id", UUID.class),
                rs.getObject("credential_pool_id", UUID.class),
                rs.getObject("credential_id", UUID.class),
                rs.getString("provider_name_snapshot"),
                rs.getString("provider_model_name_snapshot"),
                rs.getString("model_id_snapshot"),
                rs.getString("credential_name_snapshot"),
                rs.getString("status"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("provider_started_at", OffsetDateTime.class),
                rs.getObject("response_headers_at", OffsetDateTime.class),
                rs.getObject("first_token_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                (Integer) rs.getObject("dispatch_ms"),
                (Integer) rs.getObject("response_header_ms"),
                (Integer) rs.getObject("first_token_ms"),
                (Integer) rs.getObject("total_ms"),
                rs.getString("endpoint_host"),
                (Integer) rs.getObject("http_status"),
                rs.getString("provider_request_id"),
                rs.getBoolean("response_committed"),
                rs.getString("finish_reason"),
                rs.getString("error_code"),
                rs.getString("error_category"),
                rs.getString("error_stage"),
                rs.getString("error_summary"),
                rs.getBoolean("retryable"),
                (Integer) rs.getObject("retry_after_ms"),
                fromJsonMapObject(rs.getString("resolved_parameters")),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("total_tokens"),
                rs.getString("usage_source"),
                rs.getBigDecimal("input_price"),
                rs.getBigDecimal("output_price"),
                rs.getInt("price_unit"),
                rs.getString("currency"),
                rs.getBigDecimal("input_cost"),
                rs.getBigDecimal("output_cost"),
                rs.getBigDecimal("total_cost"),
                rs.getObject("settled_at", OffsetDateTime.class));
    }

    private static Map<String, String> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return ProtocolJson.protocol().readValue(json, new TypeReference<HashMap<String, String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("tags 解析失败", e);
        }
    }

    private static Map<String, Object> fromJsonMapObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return ProtocolJson.protocol().readValue(json, new TypeReference<HashMap<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("JSON列解析失败", e);
        }
    }

    private static IllegalStateException translate(String message, SQLException e) {
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
