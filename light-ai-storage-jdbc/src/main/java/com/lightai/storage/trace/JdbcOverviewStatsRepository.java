package com.lightai.storage.trace;

import com.lightai.storage.dialect.AbstractJdbcRepository;
import com.lightai.storage.dialect.DatabaseDialect;
import com.lightai.storage.dialect.DatabaseType;
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
public class JdbcOverviewStatsRepository extends AbstractJdbcRepository {

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

    public JdbcOverviewStatsRepository(String schemaName, DatabaseDialect explicitDialect) {
        super(schemaName, explicitDialect);
    }

    public JdbcOverviewStatsRepository(String schemaName) {
        super(schemaName);
    }

    public JdbcOverviewStatsRepository() {
        super();
    }

    private static final String TRACE_WHERE_BASE =
            "started_at >= ? AND started_at < ?";

    private void appendScope(StringBuilder sql, List<Object> params, OverviewFilter filter, DatabaseDialect d) {
        sql.append(" WHERE ").append(TRACE_WHERE_BASE);
        params.add(filter.startAt());
        params.add(filter.endAt());
        appendScopeConditions(sql, params, filter, d);
    }

    private String summaryColumns(DatabaseDialect d) {
        String p95Expr = (d.databaseType() == DatabaseType.POSTGRESQL)
                ? "percentile_disc(0.95) WITHIN GROUP (ORDER BY first_token_ms) FILTER (WHERE first_token_ms IS NOT NULL) AS p95_first_token_ms, "
                : "CAST(NULL AS SIGNED) AS p95_first_token_ms, ";

        return "count(*) AS request_count, "
                + "COUNT(CASE WHEN status = 'SUCCEEDED' THEN 1 END) AS success_count, "
                + "COUNT(CASE WHEN status = 'FAILED' THEN 1 END) AS failure_count, "
                + "COUNT(CASE WHEN status = 'STREAM_INTERRUPTED' THEN 1 END) AS stream_interrupted_count, "
                + "COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END) AS cancelled_count, "
                + "COUNT(CASE WHEN status IN ('RUNNING','QUEUED') THEN 1 END) AS active_count, "
                + "avg(total_ms) AS average_total_ms, "
                + p95Expr
                + "coalesce(sum(input_tokens),0) AS input_tokens, "
                + "coalesce(sum(output_tokens),0) AS output_tokens, "
                + "coalesce(sum(total_tokens),0) AS total_tokens, "
                + "coalesce(sum(retry_count),0) AS retry_count, "
                + "coalesce(sum(credential_failover_count),0) AS credential_failover_count, "
                + "coalesce(sum(fallback_count),0) AS fallback_count";
    }

    public TraceTotals summary(Connection connection, OverviewFilter filter) {
        DatabaseDialect d = dialect(connection);
        StringBuilder whereSql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        whereSql.append(" WHERE ").append(TRACE_WHERE_BASE);
        params.add(filter.startAt());
        params.add(filter.endAt());
        appendScopeConditions(whereSql, params, filter, d);
        String where = whereSql.toString();

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(summaryColumns(d)).append(", ")
                .append("coalesce(sum(att.actual_tokens),0) AS actual_tokens, ")
                .append("coalesce(sum(att.estimated_tokens),0) AS estimated_tokens ")
                .append("FROM ").append(qualify(connection, "trace")).append(" t ")
                .append("LEFT JOIN (")
                .append("SELECT trace_id, ")
                .append("sum(CASE WHEN usage_source = 'ACTUAL' THEN input_tokens + output_tokens ELSE 0 END) AS actual_tokens, ")
                .append("sum(CASE WHEN usage_source = 'ESTIMATED' THEN input_tokens + output_tokens ELSE 0 END) AS estimated_tokens ")
                .append("FROM ").append(qualify(connection, "attempt"))
                .append(" WHERE trace_id IN (SELECT trace_id FROM ").append(qualify(connection, "trace")).append(where).append(") ")
                .append("GROUP BY trace_id")
                .append(") att ON att.trace_id = t.trace_id")
                .append(where);

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            // where 条件在子查询与主查询出现两次，参数按相同顺序绑定两遍
            List<Object> doubled = new ArrayList<>(params);
            doubled.addAll(params);
            bindParameters(statement, doubled, d);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                Long p95 = getLongOrNull(rs, "p95_first_token_ms");
                if (p95 == null && d.databaseType() == DatabaseType.MYSQL) {
                    p95 = computeMySQLP95FirstTokenMs(connection, filter, d);
                }
                return new TraceTotals(
                        rs.getLong("request_count"), rs.getLong("success_count"),
                        rs.getLong("failure_count"), rs.getLong("stream_interrupted_count"),
                        rs.getLong("cancelled_count"), rs.getLong("active_count"),
                        rs.getBigDecimal("average_total_ms"),
                        p95,
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                        rs.getLong("total_tokens"), rs.getLong("actual_tokens"),
                        rs.getLong("estimated_tokens"), rs.getLong("retry_count"),
                        rs.getLong("credential_failover_count"), rs.getLong("fallback_count"));
            }
        } catch (SQLException e) {
            throw translate("概览摘要查询失败", e);
        }
    }

    private Long computeMySQLP95FirstTokenMs(Connection connection, OverviewFilter filter, DatabaseDialect d) {
        StringBuilder countSql = new StringBuilder("SELECT count(*) FROM ").append(qualify(connection, "trace"))
                .append(" WHERE first_token_ms IS NOT NULL AND ").append(TRACE_WHERE_BASE);
        List<Object> countParams = new ArrayList<>();
        countParams.add(filter.startAt());
        countParams.add(filter.endAt());
        appendScopeConditions(countSql, countParams, filter, d);
        long n = count(connection, countSql.toString(), countParams.toArray());
        if (n == 0) {
            return null;
        }
        long offset = (long) Math.ceil(n * 0.95) - 1;
        if (offset < 0) offset = 0;
        StringBuilder p95Sql = new StringBuilder("SELECT first_token_ms FROM ").append(qualify(connection, "trace"))
                .append(" WHERE first_token_ms IS NOT NULL AND ").append(TRACE_WHERE_BASE);
        List<Object> p95Params = new ArrayList<>();
        p95Params.add(filter.startAt());
        p95Params.add(filter.endAt());
        appendScopeConditions(p95Sql, p95Params, filter, d);
        p95Sql.append(" ORDER BY first_token_ms ASC LIMIT 1 OFFSET ").append(offset);
        try (PreparedStatement ps = connection.prepareStatement(p95Sql.toString())) {
            bindParameters(ps, p95Params, d);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long val = rs.getLong(1);
                    return rs.wasNull() ? null : val;
                }
            }
        } catch (SQLException e) {
            // fall back to null
        }
        return null;
    }

    /** 概览过滤条件主体（不含 started_at 边界，由调用方先行追加）。 */
    private void appendScopeConditions(StringBuilder sql, List<Object> params, OverviewFilter filter, DatabaseDialect d) {
        if (filter.applications() != null && !filter.applications().isEmpty()) {
            sql.append(" AND application IN (")
                    .append(inPlaceholders(filter.applications().size())).append(")");
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
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT currency, coalesce(sum(input_cost),0) AS input_cost, "
                + "coalesce(sum(output_cost),0) AS output_cost, coalesce(sum(total_cost),0) AS total_cost FROM ")
                .append(qualify(connection, "trace"));
        List<Object> params = new ArrayList<>();
        appendScope(sql, params, filter, d);
        sql.append(" GROUP BY currency ORDER BY currency ASC");
        return queryList(connection, sql.toString(), params, (rs, dl) -> new CurrencyAmount(
                rs.getString("currency"), rs.getBigDecimal("input_cost"),
                rs.getBigDecimal("output_cost"), rs.getBigDecimal("total_cost")));
    }

    private String formatBucketExpression(Connection connection, String bucketPrecision, String timezone,
                                         List<Object> params, DatabaseDialect d) {
        if (d.databaseType() == DatabaseType.POSTGRESQL) {
            params.add(timezone);
            params.add(timezone);
            return "(date_trunc('" + bucketPrecision + "', started_at AT TIME ZONE ?) AT TIME ZONE ?)";
        }
        String offsetStr;
        try {
            java.time.ZoneId zone = java.time.ZoneId.of(timezone);
            offsetStr = zone.getRules().getOffset(java.time.Instant.now()).getId();
            if ("Z".equalsIgnoreCase(offsetStr)) {
                offsetStr = "+00:00";
            }
        } catch (Exception e) {
            offsetStr = "+00:00";
        }
        String pattern = "hour".equalsIgnoreCase(bucketPrecision) ? "%Y-%m-%d %H:00:00" : "%Y-%m-%d 00:00:00";
        params.add(offsetStr);
        params.add(offsetStr);
        return "CONVERT_TZ(DATE_FORMAT(CONVERT_TZ(started_at, '+00:00', ?), '" + pattern + "'), ?, '+00:00')";
    }

    /** bucket_precision 为 hour 或 day；桶在配置时区自然小时/自然日上截断。 */
    public List<BucketTraceTotals> trendBuckets(Connection connection, OverviewFilter filter,
                                                String bucketPrecision, String timezone) {
        DatabaseDialect d = dialect(connection);
        List<Object> params = new ArrayList<>();
        String bucketExpr = formatBucketExpression(connection, bucketPrecision, timezone, params, d);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(bucketExpr).append(" AS bucket_start, ")
                .append(summaryColumns(d)).append(" FROM ").append(qualify(connection, "trace"));
        appendScope(sql, params, filter, d);
        sql.append(" GROUP BY 1 ORDER BY 1 ASC");
        return queryList(connection, sql.toString(), params, (rs, dl) -> new BucketTraceTotals(
                dl.readOffsetDateTime(rs, "bucket_start"),
                rs.getLong("request_count"), rs.getLong("success_count"),
                rs.getLong("failure_count"), rs.getBigDecimal("average_total_ms"),
                getLongOrNull(rs, "p95_first_token_ms"), rs.getLong("total_tokens"),
                rs.getLong("retry_count"), rs.getLong("fallback_count")));
    }

    public List<BucketCurrencyAmount> costsByBucket(Connection connection, OverviewFilter filter,
                                                    String bucketPrecision, String timezone) {
        DatabaseDialect d = dialect(connection);
        List<Object> params = new ArrayList<>();
        String bucketExpr = formatBucketExpression(connection, bucketPrecision, timezone, params, d);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(bucketExpr).append(" AS bucket_start, ")
                .append("currency, coalesce(sum(total_cost),0) AS total_cost FROM ").append(qualify(connection, "trace"));
        appendScope(sql, params, filter, d);
        sql.append(" GROUP BY 1, currency ORDER BY 1 ASC, currency ASC");
        return queryList(connection, sql.toString(), params, (rs, dl) -> new BucketCurrencyAmount(
                dl.readOffsetDateTime(rs, "bucket_start"), rs.getString("currency"),
                rs.getBigDecimal("total_cost")));
    }

    // ---- 概览异常与筛选项 ----

    public long countCircuitsByState(Connection connection, String state) {
        String sql = "SELECT count(*) FROM " + qualify(connection, "circuit_state") + " WHERE state = ?";
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
                       (SELECT count(*) FROM %s ce WHERE ce.circuit_id = cs.id) AS occurrence_count
                  FROM %s cs
                  LEFT JOIN %s pm ON pm.id = cs.provider_model_id
                  LEFT JOIN %s p ON p.id = pm.provider_id
                 WHERE cs.state IN ('OPEN','HALF_OPEN')
                 ORDER BY CASE cs.state WHEN 'OPEN' THEN 0 ELSE 1 END,
                          occurrence_count DESC, cs.updated_at DESC
                """.strip().formatted(
                        qualify(connection, "circuit_event"),
                        qualify(connection, "circuit_state"),
                        qualify(connection, "provider_model"),
                        qualify(connection, "provider"));
        return queryList(connection, sql, List.of(), (rs, dl) -> new CircuitItem(
                dl.readUuid(rs, "id"), rs.getString("state"),
                rs.getString("provider_name"), rs.getString("model_name"),
                rs.getLong("occurrence_count"), dl.readOffsetDateTime(rs, "updated_at"),
                rs.getString("last_reason")));
    }

    public long countUnavailableCandidates(Connection connection) {
        String sql = """
                SELECT count(*) FROM %s rc
                  JOIN %s s
                    ON s.entity_type = 'PROVIDER_MODEL' AND s.entity_id = rc.provider_model_id
                 WHERE rc.enabled AND s.connection_status = 'UNAVAILABLE'
                """.strip().formatted(
                        qualify(connection, "route_candidate"),
                        qualify(connection, "object_runtime_state"));
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
                  FROM %s rc
                  JOIN %s pm ON pm.id = rc.provider_model_id
                  JOIN %s ma ON ma.id = rc.alias_id
                  LEFT JOIN %s p ON p.id = pm.provider_id
                  JOIN %s s
                    ON s.entity_type = 'PROVIDER_MODEL' AND s.entity_id = rc.provider_model_id
                 WHERE rc.enabled AND s.connection_status = 'UNAVAILABLE'
                 ORDER BY s.updated_at DESC
                """.strip().formatted(
                        qualify(connection, "route_candidate"),
                        qualify(connection, "provider_model"),
                        qualify(connection, "model_alias"),
                        qualify(connection, "provider"),
                        qualify(connection, "object_runtime_state"));
        return queryList(connection, sql, List.of(), (rs, dl) -> new UnavailableCandidateItem(
                dl.readUuid(rs, "id"), rs.getString("alias_name"),
                rs.getString("provider_name"), rs.getString("model_name"),
                dl.readOffsetDateTime(rs, "updated_at")));
    }

    public long countInvalidCredentials(Connection connection) {
        String sql = """
                SELECT count(*) FROM %s c
                  JOIN %s s
                    ON s.entity_type = 'CREDENTIAL' AND s.entity_id = c.id
                 WHERE c.deleted_at IS NULL AND s.health_status = 'INVALID'
                """.strip().formatted(
                        qualify(connection, "credential"),
                        qualify(connection, "object_runtime_state"));
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
                  FROM %s c
                  JOIN %s s
                    ON s.entity_type = 'CREDENTIAL' AND s.entity_id = c.id
                  LEFT JOIN %s cp ON cp.id = c.pool_id
                  LEFT JOIN %s p ON p.id = cp.provider_id
                 WHERE c.deleted_at IS NULL AND s.health_status = 'INVALID'
                 ORDER BY s.updated_at DESC
                """.strip().formatted(
                        qualify(connection, "credential"),
                        qualify(connection, "object_runtime_state"),
                        qualify(connection, "credential_pool"),
                        qualify(connection, "provider"));
        return queryList(connection, sql, List.of(), (rs, dl) -> new InvalidCredentialItem(
                dl.readUuid(rs, "id"), rs.getString("name"),
                rs.getString("provider_name"), dl.readOffsetDateTime(rs, "updated_at"),
                rs.getString("last_reason")));
    }

    public long countFailureTraces(Connection connection, OverviewFilter filter) {
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM ").append(qualify(connection, "trace"));
        List<Object> params = new ArrayList<>();
        appendScope(sql, params, filter, d);
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
        DatabaseDialect d = dialect(connection);
        StringBuilder sql = new StringBuilder("SELECT trace_id, application, alias, status, error_code, "
                + "error_summary, coalesce(ended_at, updated_at) AS latest_at FROM ")
                .append(qualify(connection, "trace"));
        List<Object> params = new ArrayList<>();
        appendScope(sql, params, filter, d);
        sql.append(" AND status IN ('FAILED','STREAM_INTERRUPTED')")
                .append(" ORDER BY latest_at DESC LIMIT ?");
        params.add(limit);
        return queryList(connection, sql.toString(), params, (rs, dl) -> new FailureTraceItem(
                rs.getString("trace_id"), rs.getString("application"), rs.getString("alias"),
                rs.getString("status"), rs.getString("error_code"), rs.getString("error_summary"),
                dl.readOffsetDateTime(rs, "latest_at")));
    }

    // ---- 筛选选项 ----

    /** 币种选项来源：usage_aggregate 现存币种（FE-031）。 */
    public List<String> distinctUsageCurrencies(Connection connection) {
        String sql = "SELECT DISTINCT currency FROM " + qualify(connection, "usage_aggregate")
                + " ORDER BY currency ASC";
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
        String sql = "SELECT DISTINCT application FROM " + qualify(connection, "trace")
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
        String sql = "SELECT id, alias AS name FROM " + qualify(connection, "model_alias")
                + " WHERE deleted_at IS NULL ORDER BY alias ASC";
        return queryList(connection, sql, List.of(), (rs, dl) -> new OptionRef(
                dl.readUuid(rs, "id"), rs.getString("name")));
    }

    public List<OptionRef> providerOptions(Connection connection) {
        String sql = "SELECT id, name FROM " + qualify(connection, "provider")
                + " WHERE deleted_at IS NULL ORDER BY name ASC";
        return queryList(connection, sql, List.of(), (rs, dl) -> new OptionRef(
                dl.readUuid(rs, "id"), rs.getString("name")));
    }

    /** 指定 Alias 时 Provider 收敛为该 Alias 候选使用的 Provider（FE-031）。 */
    public List<OptionRef> providerOptionsByAlias(Connection connection, UUID aliasId) {
        String sql = """
                SELECT DISTINCT p.id, p.name
                  FROM %s rc
                  JOIN %s pm ON pm.id = rc.provider_model_id
                  JOIN %s p ON p.id = pm.provider_id
                 WHERE rc.alias_id = ?
                 ORDER BY p.name ASC
                """.strip().formatted(
                        qualify(connection, "route_candidate"),
                        qualify(connection, "provider_model"),
                        qualify(connection, "provider"));
        return queryList(connection, sql, List.<Object>of(aliasId), (rs, dl) -> new OptionRef(
                dl.readUuid(rs, "id"), rs.getString("name")));
    }

    // ---- 通用小工具 ----

    private long count(Connection connection, String sql, Object... params) {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, List.of(params), d);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("概览统计查询失败", e);
        }
    }

    private <T> List<T> queryList(Connection connection, String sql, List<Object> params,
                                  RowMapper<T> mapper) {
        DatabaseDialect d = dialect(connection);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, params, d);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs, d));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("概览统计查询失败", e);
        }
    }

    private interface RowMapper<T> {
        T map(ResultSet rs, DatabaseDialect d) throws SQLException;
    }
}
