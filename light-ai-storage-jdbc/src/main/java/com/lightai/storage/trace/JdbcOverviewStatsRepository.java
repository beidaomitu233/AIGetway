package com.lightai.storage.trace;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 运行概览统计查询（DATABASE_PLAN trace/attempt/circuit_state/object_runtime_state，BE-034）。
 * 请求状态指标来自 Trace 同范围快照；实际/估算 Token 拆分经 Attempt 汇总，避免 MIXED 不可拆。
 * 概览异常项合并：OPEN/HALF_OPEN 熔断、不可用候选、INVALID 凭证与近期失败 Trace。
 */
public class JdbcOverviewStatsRepository {

    /** 概览公共筛选（别名/Provider 为单选；applications 为身份范围注入结果）。 */
    public record OverviewFilter(
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            List<String> applications,
            UUID aliasId,
            UUID providerId) {
    }

    public record TraceTotals(
            long requestCount,
            long successCount,
            long failureCount,
            long streamInterruptedCount,
            long cancelledCount,
            long activeCount,
            BigDecimal averageTotalMs,
            Long p95FirstTokenMs,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long actualTokens,
            long estimatedTokens,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount) {
    }

    public record CurrencyAmount(String currency, BigDecimal inputCost, BigDecimal outputCost,
                                 BigDecimal totalCost) {
    }

    /** 桶级分币种费用（概览趋势用）。 */
    public record BucketCurrencyAmount(OffsetDateTime bucketStart, String currency,
                                       BigDecimal totalCost) {
    }

    public record BucketTraceTotals(
            OffsetDateTime bucketStart,
            long requestCount,
            long successCount,
            long failureCount,
            BigDecimal averageTotalMs,
            Long p95FirstTokenMs,
            long totalTokens,
            long retryCount,
            long fallbackCount) {
    }

    private final String schemaName;

    public JdbcOverviewStatsRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcOverviewStatsRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    private static final String TRACE_WHERE_BASE =
            "started_at >= ? AND started_at < ?";

    private void appendScope(StringBuilder sql, List<Object> params, OverviewFilter filter) {
        sql.append(" WHERE ").append(TRACE_WHERE_BASE);
        params.add(filter.startAt());
        params.add(filter.endAt());
        appendScopeConditions(sql, params, filter);
    }

    private static final String SUMMARY_COLUMNS =
            "count(*) AS request_count, "
                    + "count(*) FILTER (WHERE status = 'SUCCEEDED') AS success_count, "
                    + "count(*) FILTER (WHERE status = 'FAILED') AS failure_count, "
                    + "count(*) FILTER (WHERE status = 'STREAM_INTERRUPTED') AS stream_interrupted_count, "
                    + "count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled_count, "
                    + "count(*) FILTER (WHERE status IN ('RUNNING','QUEUED')) AS active_count, "
                    + "avg(total_ms) AS average_total_ms, "
                    + "percentile_disc(0.95) WITHIN GROUP (ORDER BY first_token_ms) "
                    + "FILTER (WHERE first_token_ms IS NOT NULL) AS p95_first_token_ms, "
                    + "coalesce(sum(input_tokens),0) AS input_tokens, "
                    + "coalesce(sum(output_tokens),0) AS output_tokens, "
                    + "coalesce(sum(total_tokens),0) AS total_tokens, "
                    + "coalesce(sum(retry_count),0) AS retry_count, "
                    + "coalesce(sum(credential_failover_count),0) AS credential_failover_count, "
                    + "coalesce(sum(fallback_count),0) AS fallback_count";

    private static final String ACTUAL_ESTIMATED_SUBQUERY =
            "att AS (SELECT trace_id, "
                    + "sum(input_tokens + output_tokens) FILTER (WHERE usage_source = 'ACTUAL') AS actual_tokens, "
                    + "sum(input_tokens + output_tokens) FILTER (WHERE usage_source = 'ESTIMATED') AS estimated_tokens "
                    + "FROM %1$s.attempt WHERE trace_id IN (SELECT trace_id FROM %1$s.trace%2$s) GROUP BY trace_id) ";

    public TraceTotals summary(Connection connection, OverviewFilter filter) {
        StringBuilder whereSql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        whereSql.append(" WHERE ").append(TRACE_WHERE_BASE);
        params.add(filter.startAt());
        params.add(filter.endAt());
        appendScopeConditions(whereSql, params, filter);
        String where = whereSql.toString();
        StringBuilder sql = new StringBuilder("WITH ")
                .append(ACTUAL_ESTIMATED_SUBQUERY.formatted(schemaName, where))
                .append("SELECT ").append(SUMMARY_COLUMNS).append(", ")
                .append("coalesce(sum(att.actual_tokens),0) AS actual_tokens, ")
                .append("coalesce(sum(att.estimated_tokens),0) AS estimated_tokens ")
                .append("FROM ").append(qualifiedTrace()).append(" t ")
                .append("LEFT JOIN att ON att.trace_id = t.trace_id")
                .append(where);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            // where 条件在 CTE 与主查询出现两次，参数按相同顺序绑定两遍
            List<Object> doubled = new ArrayList<>(params);
            doubled.addAll(params);
            bind(statement, doubled);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new TraceTotals(
                        rs.getLong("request_count"), rs.getLong("success_count"),
                        rs.getLong("failure_count"), rs.getLong("stream_interrupted_count"),
                        rs.getLong("cancelled_count"), rs.getLong("active_count"),
                        rs.getBigDecimal("average_total_ms"),
                        (Long) rs.getObject("p95_first_token_ms"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                        rs.getLong("total_tokens"), rs.getLong("actual_tokens"),
                        rs.getLong("estimated_tokens"), rs.getLong("retry_count"),
                        rs.getLong("credential_failover_count"), rs.getLong("fallback_count"));
            }
        } catch (SQLException e) {
            throw translate("概览摘要查询失败", e);
        }
    }

    /** 概览过滤条件主体（不含 started_at 边界，由调用方先行追加）。 */
    private void appendScopeConditions(StringBuilder sql, List<Object> params, OverviewFilter filter) {
        if (filter.applications() != null && !filter.applications().isEmpty()) {
            sql.append(" AND application IN (")
                    .append(placeholderList(filter.applications().size())).append(")");
            params.addAll(filter.applications());
        }
        if (filter.aliasId() != null) {
            sql.append(" AND alias_id = ?");
            params.add(filter.aliasId());
        }
        if (filter.providerId() != null) {
            sql.append(" AND final_provider_id = ?");
            params.add(filter.providerId());
        }
    }

    public List<CurrencyAmount> costsByCurrency(Connection connection, OverviewFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT currency, coalesce(sum(input_cost),0) AS input_cost, "
                + "coalesce(sum(output_cost),0) AS output_cost, coalesce(sum(total_cost),0) AS total_cost FROM ")
                .append(qualifiedTrace());
        List<Object> params = new ArrayList<>();
        appendScope(sql, params, filter);
        sql.append(" GROUP BY currency ORDER BY currency ASC");
        return queryList(connection, sql.toString(), params, rs -> new CurrencyAmount(
                rs.getString("currency"), rs.getBigDecimal("input_cost"),
                rs.getBigDecimal("output_cost"), rs.getBigDecimal("total_cost")));
    }

    /** bucket_precision 为 hour 或 day；桶在配置时区自然小时/自然日上截断。 */
    public List<BucketTraceTotals> trendBuckets(Connection connection, OverviewFilter filter,
                                                String bucketPrecision, String timezone) {
        StringBuilder sql = new StringBuilder("SELECT (date_trunc('")
                .append(bucketPrecision).append("', started_at AT TIME ZONE ?) AT TIME ZONE ?) AS bucket_start, ")
                .append(SUMMARY_COLUMNS).append(" FROM ").append(qualifiedTrace());
        List<Object> params = new ArrayList<>();
        params.add(timezone);
        params.add(timezone);
        appendScope(sql, params, filter);
        sql.append(" GROUP BY 1 ORDER BY 1 ASC");
        return queryList(connection, sql.toString(), params, rs -> new BucketTraceTotals(
                rs.getObject("bucket_start", OffsetDateTime.class),
                rs.getLong("request_count"), rs.getLong("success_count"),
                rs.getLong("failure_count"), rs.getBigDecimal("average_total_ms"),
                (Long) rs.getObject("p95_first_token_ms"), rs.getLong("total_tokens"),
                rs.getLong("retry_count"), rs.getLong("fallback_count")));
    }

    public List<BucketCurrencyAmount> costsByBucket(Connection connection, OverviewFilter filter,
                                                    String bucketPrecision, String timezone) {
        StringBuilder sql = new StringBuilder("SELECT (date_trunc('")
                .append(bucketPrecision).append("', started_at AT TIME ZONE ?) AT TIME ZONE ?) AS bucket_start, "
                + "currency, coalesce(sum(total_cost),0) AS total_cost FROM ").append(qualifiedTrace());
        List<Object> params = new ArrayList<>();
        params.add(timezone);
        params.add(timezone);
        appendScope(sql, params, filter);
        sql.append(" GROUP BY 1, currency ORDER BY 1 ASC, currency ASC");
        return queryList(connection, sql.toString(), params, rs -> new BucketCurrencyAmount(
                rs.getObject("bucket_start", OffsetDateTime.class), rs.getString("currency"),
                rs.getBigDecimal("total_cost")));
    }

    // ---- 概览异常与筛选项 ----

    public long countCircuitsByState(Connection connection, String state) {
        String sql = "SELECT count(*) FROM " + schemaName + ".circuit_state WHERE state = ?";
        return count(connection, sql, state);
    }

    /** 熔断异常项：名称为配置快照，事件数来自 circuit_event 计数。 */
    public record CircuitItem(
            UUID id,
            String state,
            String providerName,
            String modelName,
            long occurrenceCount,
            OffsetDateTime latestAt,
            String lastReason) {
    }

    public List<CircuitItem> circuitItems(Connection connection) {
        String sql = """
                SELECT cs.id, cs.state, cs.updated_at, cs.last_reason,
                       p.name AS provider_name, pm.display_name AS model_name,
                       (SELECT count(*) FROM %s.circuit_event ce WHERE ce.circuit_id = cs.id) AS occurrence_count
                  FROM %s.circuit_state cs
                  LEFT JOIN %s.provider_model pm ON pm.id = cs.provider_model_id
                  LEFT JOIN %s.provider p ON p.id = pm.provider_id
                 WHERE cs.state IN ('OPEN','HALF_OPEN')
                 ORDER BY CASE cs.state WHEN 'OPEN' THEN 0 ELSE 1 END,
                          occurrence_count DESC, cs.updated_at DESC
                """.strip().formatted(schemaName, schemaName, schemaName, schemaName);
        return queryList(connection, sql, List.of(), rs -> new CircuitItem(
                rs.getObject("id", UUID.class), rs.getString("state"),
                rs.getString("provider_name"), rs.getString("model_name"),
                rs.getLong("occurrence_count"), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("last_reason")));
    }

    public long countUnavailableCandidates(Connection connection) {
        String sql = """
                SELECT count(*) FROM %s.route_candidate rc
                  JOIN %s.object_runtime_state s
                    ON s.entity_type = 'PROVIDER_MODEL' AND s.entity_id = rc.provider_model_id
                 WHERE rc.enabled AND s.connection_status = 'UNAVAILABLE'
                """.strip().formatted(schemaName, schemaName);
        return count(connection, sql);
    }

    public record UnavailableCandidateItem(
            UUID id,
            String aliasName,
            String providerName,
            String modelName,
            OffsetDateTime latestAt) {
    }

    public List<UnavailableCandidateItem> unavailableCandidateItems(Connection connection) {
        String sql = """
                SELECT rc.id, ma.alias AS alias_name, p.name AS provider_name,
                       pm.display_name AS model_name, s.updated_at
                  FROM %s.route_candidate rc
                  JOIN %s.provider_model pm ON pm.id = rc.provider_model_id
                  JOIN %s.model_alias ma ON ma.id = rc.alias_id
                  LEFT JOIN %s.provider p ON p.id = pm.provider_id
                  JOIN %s.object_runtime_state s
                    ON s.entity_type = 'PROVIDER_MODEL' AND s.entity_id = rc.provider_model_id
                 WHERE rc.enabled AND s.connection_status = 'UNAVAILABLE'
                 ORDER BY s.updated_at DESC
                """.strip().formatted(schemaName, schemaName, schemaName, schemaName, schemaName);
        return queryList(connection, sql, List.of(), rs -> new UnavailableCandidateItem(
                rs.getObject("id", UUID.class), rs.getString("alias_name"),
                rs.getString("provider_name"), rs.getString("model_name"),
                rs.getObject("updated_at", OffsetDateTime.class)));
    }

    public long countInvalidCredentials(Connection connection) {
        String sql = """
                SELECT count(*) FROM %s.credential c
                  JOIN %s.object_runtime_state s
                    ON s.entity_type = 'CREDENTIAL' AND s.entity_id = c.id
                 WHERE c.deleted_at IS NULL AND s.health_status = 'INVALID'
                """.strip().formatted(schemaName, schemaName);
        return count(connection, sql);
    }

    public record InvalidCredentialItem(
            UUID id,
            String name,
            String providerName,
            OffsetDateTime latestAt,
            String lastReason) {
    }

    public List<InvalidCredentialItem> invalidCredentialItems(Connection connection) {
        String sql = """
                SELECT c.id, c.name, p.name AS provider_name, s.updated_at,
                       s.last_error_summary AS last_reason
                  FROM %s.credential c
                  JOIN %s.object_runtime_state s
                    ON s.entity_type = 'CREDENTIAL' AND s.entity_id = c.id
                  LEFT JOIN %s.credential_pool cp ON cp.id = c.pool_id
                  LEFT JOIN %s.provider p ON p.id = cp.provider_id
                 WHERE c.deleted_at IS NULL AND s.health_status = 'INVALID'
                 ORDER BY s.updated_at DESC
                """.strip().formatted(schemaName, schemaName, schemaName, schemaName);
        return queryList(connection, sql, List.of(), rs -> new InvalidCredentialItem(
                rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("provider_name"), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("last_reason")));
    }

    public long countFailureTraces(Connection connection, OverviewFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualifiedTrace());
        List<Object> params = new ArrayList<>();
        appendScope(sql, params, filter);
        sql.append(" AND status IN ('FAILED','STREAM_INTERRUPTED')");
        return count(connection, sql.toString(), params.toArray());
    }

    public record FailureTraceItem(
            String traceId,
            String application,
            String alias,
            String status,
            String errorCode,
            String errorSummary,
            OffsetDateTime latestAt) {
    }

    public List<FailureTraceItem> failureTraceItems(Connection connection, OverviewFilter filter,
                                                    int limit) {
        StringBuilder sql = new StringBuilder("SELECT trace_id, application, alias, status, error_code, "
                + "error_summary, coalesce(ended_at, updated_at) AS latest_at FROM ")
                .append(qualifiedTrace());
        List<Object> params = new ArrayList<>();
        appendScope(sql, params, filter);
        sql.append(" AND status IN ('FAILED','STREAM_INTERRUPTED')")
                .append(" ORDER BY latest_at DESC LIMIT ?");
        params.add(limit);
        return queryList(connection, sql.toString(), params, rs -> new FailureTraceItem(
                rs.getString("trace_id"), rs.getString("application"), rs.getString("alias"),
                rs.getString("status"), rs.getString("error_code"), rs.getString("error_summary"),
                rs.getObject("latest_at", OffsetDateTime.class)));
    }

    // ---- 筛选选项 ----

    /** 币种选项来源：usage_aggregate 现存币种（FE-031）。 */
    public List<String> distinctUsageCurrencies(Connection connection) {
        String sql = "SELECT DISTINCT currency FROM " + schemaName
                + ".usage_aggregate ORDER BY currency ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<String> currencies = new ArrayList<>();
            while (rs.next()) {
                currencies.add(rs.getString("currency"));
            }
            return List.copyOf(currencies);
        } catch (SQLException e) {
            throw translate("币种查询失败", e);
        }
    }

    public List<String> distinctApplications(Connection connection, int limit) {
        String sql = "SELECT DISTINCT application FROM " + qualifiedTrace()
                + " ORDER BY application ASC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> apps = new ArrayList<>();
                while (rs.next()) {
                    apps.add(rs.getString("application"));
                }
                return List.copyOf(apps);
            }
        } catch (SQLException e) {
            throw translate("应用筛选查询失败", e);
        }
    }

    public record OptionRef(UUID id, String name) {
    }

    public List<OptionRef> aliasOptions(Connection connection) {
        String sql = "SELECT id, alias AS name FROM " + schemaName
                + ".model_alias WHERE deleted_at IS NULL ORDER BY alias ASC";
        return queryList(connection, sql, List.of(), rs -> new OptionRef(
                rs.getObject("id", UUID.class), rs.getString("name")));
    }

    public List<OptionRef> providerOptions(Connection connection) {
        String sql = "SELECT id, name FROM " + schemaName
                + ".provider WHERE deleted_at IS NULL ORDER BY name ASC";
        return queryList(connection, sql, List.of(), rs -> new OptionRef(
                rs.getObject("id", UUID.class), rs.getString("name")));
    }

    /** 指定 Alias 时 Provider 收敛为该 Alias 候选使用的 Provider（FE-031）。 */
    public List<OptionRef> providerOptionsByAlias(Connection connection, UUID aliasId) {
        String sql = """
                SELECT DISTINCT p.id, p.name
                  FROM %s.route_candidate rc
                  JOIN %s.provider_model pm ON pm.id = rc.provider_model_id
                  JOIN %s.provider p ON p.id = pm.provider_id
                 WHERE rc.alias_id = ?
                 ORDER BY p.name ASC
                """.strip().formatted(schemaName, schemaName, schemaName);
        return queryList(connection, sql, List.<Object>of(aliasId), rs -> new OptionRef(
                rs.getObject("id", UUID.class), rs.getString("name")));
    }

    // ---- 通用小工具 ----

    private long count(Connection connection, String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, List.of(params));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("概览统计查询失败", e);
        }
    }

    private static String placeholderList(int size) {
        return String.join(", ", java.util.Collections.nCopies(size, "?"));
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    private <T> List<T> queryList(Connection connection, String sql, List<Object> params,
                                  RowMapper<T> mapper) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("概览统计查询失败", e);
        }
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private String qualifiedTrace() {
        return schemaName + ".trace";
    }

    private static IllegalStateException translate(String message, SQLException e) {
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
