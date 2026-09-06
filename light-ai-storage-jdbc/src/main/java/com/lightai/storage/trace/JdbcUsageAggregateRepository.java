package com.lightai.storage.trace;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * usage_aggregate JDBC 仓储（DATABASE_PLAN 第 27 表，BE-033/035）。
 * 聚合更新用原子 upsert 增量；直方图以 jsonb_each 合并，不在 SQL 外读改写，
 * 避免两个 worker 同时命中同一聚合行时丢失计数。
 * 请求/执行贡献按相同 dimension_key 合并由调用方（ContributionCalculator）保证。
 */
public class JdbcUsageAggregateRepository {

    private final String schemaName;

    public JdbcUsageAggregateRepository(String schemaName) {
        this.schemaName = schemaName;
    }

    public JdbcUsageAggregateRepository() {
        this(com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    /** 一次聚合贡献：HOUR 与 DAY 各写一行（同 dimension_key 与币种）。 */
    public record Contribution(
            String granularity,
            OffsetDateTime bucketStart,
            OffsetDateTime bucketEnd,
            String dimensionKey,
            String application,
            String project,
            String tenant,
            UUID aliasId,
            UUID providerId,
            UUID providerModelId,
            UUID credentialPoolId,
            UUID credentialId,
            String traceStatus,
            String errorCode,
            String usageSource,
            boolean requestedStream,
            String currency,
            Map<String, String> dimensionNames,
            long requestCount,
            long successCount,
            long failureCount,
            long cancelledCount,
            long streamInterruptedCount,
            long queuedCount,
            long streamCount,
            long attemptCount,
            long initialCount,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount,
            long halfOpenProbeCount,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long actualInputTokens,
            long actualOutputTokens,
            long estimatedInputTokens,
            long estimatedOutputTokens,
            BigDecimal inputCost,
            BigDecimal outputCost,
            BigDecimal totalCost,
            long totalMsSum,
            long totalMsCount,
            long firstTokenMsSum,
            long firstTokenMsCount,
            long queuedMsSum,
            Map<String, Long> latencyHistogram,
            Map<String, Long> firstTokenHistogram) {
    }

    /** 原子合并稀疏毫秒直方图：既有行与新增值按键求和，不做桶近似，不在语句外读改写。 */
    private static final String HISTOGRAM_MERGE_SQL = """
            (SELECT COALESCE(jsonb_object_agg(t.key, t.val), '{}'::jsonb) FROM (
               SELECT e.key, sum((e.value)::bigint) AS val
                 FROM (SELECT key, value FROM jsonb_each(usage_aggregate.%s)
                       UNION ALL
                       SELECT key, value FROM jsonb_each(EXCLUDED.%s)) e
                GROUP BY e.key))""".strip();

    private static final String UPSERT_SQL = """
            INSERT INTO %s.usage_aggregate
              (id, granularity, bucket_start, bucket_end, dimension_key, application, project, tenant,
               alias_id, provider_id, provider_model_id, credential_pool_id, credential_id,
               trace_status, error_code, usage_source, requested_stream, currency, dimension_names,
               request_count, success_count, failure_count, cancelled_count, stream_interrupted_count,
               queued_count, stream_count, attempt_count, initial_count, retry_count,
               credential_failover_count, fallback_count, half_open_probe_count,
               input_tokens, output_tokens, total_tokens,
               actual_input_tokens, actual_output_tokens, estimated_input_tokens, estimated_output_tokens,
               input_cost, output_cost, total_cost,
               total_ms_sum, total_ms_count, first_token_ms_sum, first_token_ms_count, queued_ms_sum,
               latency_histogram, first_token_histogram, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?::jsonb, ?::jsonb, now(), now())
            ON CONFLICT (granularity, bucket_start, dimension_key, currency) DO UPDATE SET
              request_count = usage_aggregate.request_count + EXCLUDED.request_count,
              success_count = usage_aggregate.success_count + EXCLUDED.success_count,
              failure_count = usage_aggregate.failure_count + EXCLUDED.failure_count,
              cancelled_count = usage_aggregate.cancelled_count + EXCLUDED.cancelled_count,
              stream_interrupted_count = usage_aggregate.stream_interrupted_count + EXCLUDED.stream_interrupted_count,
              queued_count = usage_aggregate.queued_count + EXCLUDED.queued_count,
              stream_count = usage_aggregate.stream_count + EXCLUDED.stream_count,
              attempt_count = usage_aggregate.attempt_count + EXCLUDED.attempt_count,
              initial_count = usage_aggregate.initial_count + EXCLUDED.initial_count,
              retry_count = usage_aggregate.retry_count + EXCLUDED.retry_count,
              credential_failover_count = usage_aggregate.credential_failover_count + EXCLUDED.credential_failover_count,
              fallback_count = usage_aggregate.fallback_count + EXCLUDED.fallback_count,
              half_open_probe_count = usage_aggregate.half_open_probe_count + EXCLUDED.half_open_probe_count,
              input_tokens = usage_aggregate.input_tokens + EXCLUDED.input_tokens,
              output_tokens = usage_aggregate.output_tokens + EXCLUDED.output_tokens,
              total_tokens = usage_aggregate.total_tokens + EXCLUDED.total_tokens,
              actual_input_tokens = usage_aggregate.actual_input_tokens + EXCLUDED.actual_input_tokens,
              actual_output_tokens = usage_aggregate.actual_output_tokens + EXCLUDED.actual_output_tokens,
              estimated_input_tokens = usage_aggregate.estimated_input_tokens + EXCLUDED.estimated_input_tokens,
              estimated_output_tokens = usage_aggregate.estimated_output_tokens + EXCLUDED.estimated_output_tokens,
              input_cost = usage_aggregate.input_cost + EXCLUDED.input_cost,
              output_cost = usage_aggregate.output_cost + EXCLUDED.output_cost,
              total_cost = usage_aggregate.total_cost + EXCLUDED.total_cost,
              total_ms_sum = usage_aggregate.total_ms_sum + EXCLUDED.total_ms_sum,
              total_ms_count = usage_aggregate.total_ms_count + EXCLUDED.total_ms_count,
              first_token_ms_sum = usage_aggregate.first_token_ms_sum + EXCLUDED.first_token_ms_sum,
              first_token_ms_count = usage_aggregate.first_token_ms_count + EXCLUDED.first_token_ms_count,
              queued_ms_sum = usage_aggregate.queued_ms_sum + EXCLUDED.queued_ms_sum,
              latency_histogram = """ + HISTOGRAM_MERGE_SQL.formatted("latency_histogram", "latency_histogram")
            + ",\n              first_token_histogram = "
            + HISTOGRAM_MERGE_SQL.formatted("first_token_histogram", "first_token_histogram")
            + ",\n              updated_at = now()";

    public void upsertContribution(Connection connection, Contribution c) {
        try (PreparedStatement statement = connection.prepareStatement(upsertSql())) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, c.granularity());
            statement.setObject(3, c.bucketStart());
            statement.setObject(4, c.bucketEnd());
            statement.setString(5, c.dimensionKey());
            statement.setString(6, c.application());
            statement.setString(7, c.project());
            statement.setString(8, c.tenant());
            statement.setObject(9, c.aliasId());
            statement.setObject(10, c.providerId());
            statement.setObject(11, c.providerModelId());
            statement.setObject(12, c.credentialPoolId());
            statement.setObject(13, c.credentialId());
            statement.setString(14, c.traceStatus());
            statement.setString(15, c.errorCode());
            statement.setString(16, c.usageSource());
            statement.setBoolean(17, c.requestedStream());
            statement.setString(18, c.currency());
            statement.setString(19, toJson(c.dimensionNames()));
            statement.setLong(20, c.requestCount());
            statement.setLong(21, c.successCount());
            statement.setLong(22, c.failureCount());
            statement.setLong(23, c.cancelledCount());
            statement.setLong(24, c.streamInterruptedCount());
            statement.setLong(25, c.queuedCount());
            statement.setLong(26, c.streamCount());
            statement.setLong(27, c.attemptCount());
            statement.setLong(28, c.initialCount());
            statement.setLong(29, c.retryCount());
            statement.setLong(30, c.credentialFailoverCount());
            statement.setLong(31, c.fallbackCount());
            statement.setLong(32, c.halfOpenProbeCount());
            statement.setLong(33, c.inputTokens());
            statement.setLong(34, c.outputTokens());
            statement.setLong(35, c.totalTokens());
            statement.setLong(36, c.actualInputTokens());
            statement.setLong(37, c.actualOutputTokens());
            statement.setLong(38, c.estimatedInputTokens());
            statement.setLong(39, c.estimatedOutputTokens());
            statement.setBigDecimal(40, c.inputCost());
            statement.setBigDecimal(41, c.outputCost());
            statement.setBigDecimal(42, c.totalCost());
            statement.setLong(43, c.totalMsSum());
            statement.setLong(44, c.totalMsCount());
            statement.setLong(45, c.firstTokenMsSum());
            statement.setLong(46, c.firstTokenMsCount());
            statement.setLong(47, c.queuedMsSum());
            statement.setString(48, toJsonNumberKeys(c.latencyHistogram()));
            statement.setString(49, toJsonNumberKeys(c.firstTokenHistogram()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("聚合贡献写入失败", e);
        }
    }

    private String upsertSql() {
        return UPSERT_SQL.formatted(schemaName);
    }

    /** 聚合行筛选：维度多值已由服务层校验个数与枚举；范围以桶边界表达。 */
    public record AggregateFilter(
            String granularity,
            OffsetDateTime rangeStart,
            OffsetDateTime rangeEnd,
            List<String> applications,
            List<String> projects,
            List<String> tenants,
            List<String> aliasIds,
            List<String> providerIds,
            List<String> providerModelIds,
            List<String> credentialPoolIds,
            List<String> credentialIds,
            List<String> traceStatuses,
            List<String> errorCodes,
            List<String> usageSources,
            Boolean requestedStream,
            String currency) {
    }

    /** 指标合计（不含费用；费用分币种单独返回）。 */
    public record UsageTotals(
            long requestCount,
            long successCount,
            long failureCount,
            long cancelledCount,
            long queuedCount,
            long streamCount,
            long streamInterruptedCount,
            long attemptCount,
            long initialCount,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount,
            long halfOpenProbeCount,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long actualInputTokens,
            long actualOutputTokens,
            long estimatedInputTokens,
            long estimatedOutputTokens) {

        public long actualTokens() {
            return actualInputTokens + actualOutputTokens;
        }

        public long estimatedTokens() {
            return estimatedInputTokens + estimatedOutputTokens;
        }
    }

    public record CurrencyCost(String currency, BigDecimal inputCost, BigDecimal outputCost,
                               BigDecimal totalCost) {
    }

    private static final String SUM_COLUMNS =
            "coalesce(sum(request_count),0) AS request_count, "
                    + "coalesce(sum(success_count),0) AS success_count, "
                    + "coalesce(sum(failure_count),0) AS failure_count, "
                    + "coalesce(sum(cancelled_count),0) AS cancelled_count, "
                    + "coalesce(sum(queued_count),0) AS queued_count, "
                    + "coalesce(sum(stream_count),0) AS stream_count, "
                    + "coalesce(sum(stream_interrupted_count),0) AS stream_interrupted_count, "
                    + "coalesce(sum(attempt_count),0) AS attempt_count, "
                    + "coalesce(sum(initial_count),0) AS initial_count, "
                    + "coalesce(sum(retry_count),0) AS retry_count, "
                    + "coalesce(sum(credential_failover_count),0) AS credential_failover_count, "
                    + "coalesce(sum(fallback_count),0) AS fallback_count, "
                    + "coalesce(sum(half_open_probe_count),0) AS half_open_probe_count, "
                    + "coalesce(sum(input_tokens),0) AS input_tokens, "
                    + "coalesce(sum(output_tokens),0) AS output_tokens, "
                    + "coalesce(sum(total_tokens),0) AS total_tokens, "
                    + "coalesce(sum(actual_input_tokens),0) AS actual_input_tokens, "
                    + "coalesce(sum(actual_output_tokens),0) AS actual_output_tokens, "
                    + "coalesce(sum(estimated_input_tokens),0) AS estimated_input_tokens, "
                    + "coalesce(sum(estimated_output_tokens),0) AS estimated_output_tokens";

    public UsageTotals summarize(Connection connection, AggregateFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SUM_COLUMNS).append(" FROM ")
                .append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new UsageTotals(
                        rs.getLong("request_count"), rs.getLong("success_count"),
                        rs.getLong("failure_count"), rs.getLong("cancelled_count"),
                        rs.getLong("queued_count"), rs.getLong("stream_count"),
                        rs.getLong("stream_interrupted_count"), rs.getLong("attempt_count"),
                        rs.getLong("initial_count"), rs.getLong("retry_count"),
                        rs.getLong("credential_failover_count"), rs.getLong("fallback_count"),
                        rs.getLong("half_open_probe_count"), rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"), rs.getLong("total_tokens"),
                        rs.getLong("actual_input_tokens"), rs.getLong("actual_output_tokens"),
                        rs.getLong("estimated_input_tokens"), rs.getLong("estimated_output_tokens"));
            }
        } catch (SQLException e) {
            throw translate("Usage摘要查询失败", e);
        }
    }

    public List<CurrencyCost> costsByCurrency(Connection connection, AggregateFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT currency, coalesce(sum(input_cost),0) AS input_cost, "
                + "coalesce(sum(output_cost),0) AS output_cost, coalesce(sum(total_cost),0) AS total_cost FROM ")
                .append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        sql.append(" GROUP BY currency ORDER BY currency ASC");
        return queryList(connection, sql.toString(), params, rs -> new CurrencyCost(
                rs.getString("currency"), rs.getBigDecimal("input_cost"),
                rs.getBigDecimal("output_cost"), rs.getBigDecimal("total_cost")));
    }

    /** 桶行：计数与 Token 按桶合计，费用由 costsByBucket 分币种返回。 */
    public record BucketTotals(
            OffsetDateTime bucketStart,
            OffsetDateTime bucketEnd,
            long requestCount,
            long successCount,
            long failureCount,
            long streamInterruptedCount,
            long attemptCount,
            long initialCount,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount,
            long halfOpenProbeCount,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long actualInputTokens,
            long actualOutputTokens,
            long estimatedInputTokens,
            long estimatedOutputTokens) {
    }

    public record BucketCurrencyCost(OffsetDateTime bucketStart, String currency,
                                     BigDecimal inputCost, BigDecimal outputCost,
                                     BigDecimal totalCost) {
    }

    public List<BucketTotals> trendBuckets(Connection connection, AggregateFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT bucket_start, bucket_end, ").append(SUM_COLUMNS)
                .append(" FROM ").append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        sql.append(" GROUP BY bucket_start, bucket_end ORDER BY bucket_start ASC");
        return queryList(connection, sql.toString(), params, rs -> new BucketTotals(
                rs.getObject("bucket_start", OffsetDateTime.class),
                rs.getObject("bucket_end", OffsetDateTime.class),
                rs.getLong("request_count"), rs.getLong("success_count"), rs.getLong("failure_count"),
                rs.getLong("stream_interrupted_count"),
                rs.getLong("attempt_count"), rs.getLong("initial_count"), rs.getLong("retry_count"),
                rs.getLong("credential_failover_count"), rs.getLong("fallback_count"),
                rs.getLong("half_open_probe_count"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getLong("total_tokens"),
                rs.getLong("actual_input_tokens"), rs.getLong("actual_output_tokens"),
                rs.getLong("estimated_input_tokens"), rs.getLong("estimated_output_tokens")));
    }

    public List<BucketCurrencyCost> costsByBucket(Connection connection, AggregateFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT bucket_start, currency, "
                + "coalesce(sum(input_cost),0) AS input_cost, coalesce(sum(output_cost),0) AS output_cost, "
                + "coalesce(sum(total_cost),0) AS total_cost FROM ")
                .append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        sql.append(" GROUP BY bucket_start, currency ORDER BY bucket_start ASC, currency ASC");
        return queryList(connection, sql.toString(), params, rs -> new BucketCurrencyCost(
                rs.getObject("bucket_start", OffsetDateTime.class), rs.getString("currency"),
                rs.getBigDecimal("input_cost"), rs.getBigDecimal("output_cost"),
                rs.getBigDecimal("total_cost")));
    }

    /** 分组行：dimension 列值 + 币种拆行；dimension_names 快照整列返回由服务层取键。
     *  streamInterruptedCount 仅用于成功率分母，不单独输出。 */
    public record GroupRow(
            String dimensionValue,
            String currency,
            Map<String, String> dimensionNames,
            long requestCount,
            long successCount,
            long failureCount,
            long streamInterruptedCount,
            long attemptCount,
            long initialCount,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount,
            long halfOpenProbeCount,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long actualInputTokens,
            long actualOutputTokens,
            long estimatedInputTokens,
            long estimatedOutputTokens,
            BigDecimal inputCost,
            BigDecimal outputCost,
            BigDecimal totalCost) {
    }

    public List<GroupRow> groupRows(Connection connection, AggregateFilter filter, String dimensionColumn) {
        StringBuilder sql = new StringBuilder("SELECT ").append(dimensionColumn)
                .append(" AS dimension_value, currency, max(dimension_names) AS dimension_names, ")
                .append(SUM_COLUMNS).append(", coalesce(sum(input_cost),0) AS input_cost, "
                        + "coalesce(sum(output_cost),0) AS output_cost, "
                        + "coalesce(sum(total_cost),0) AS total_cost FROM ")
                .append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        sql.append(" GROUP BY ").append(dimensionColumn).append(", currency");
        return queryList(connection, sql.toString(), params, rs -> {
            Map<String, String> names = namesFromJson(rs.getString("dimension_names"));
            return new GroupRow(
                    rs.getString("dimension_value"), rs.getString("currency"), names,
                    rs.getLong("request_count"), rs.getLong("success_count"), rs.getLong("failure_count"),
                    rs.getLong("stream_interrupted_count"),
                    rs.getLong("attempt_count"), rs.getLong("initial_count"), rs.getLong("retry_count"),
                    rs.getLong("credential_failover_count"), rs.getLong("fallback_count"),
                    rs.getLong("half_open_probe_count"), rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"), rs.getLong("total_tokens"),
                    rs.getLong("actual_input_tokens"), rs.getLong("actual_output_tokens"),
                    rs.getLong("estimated_input_tokens"), rs.getLong("estimated_output_tokens"),
                    rs.getBigDecimal("input_cost"), rs.getBigDecimal("output_cost"),
                    rs.getBigDecimal("total_cost"));
        });
    }

    /** 数据水位：命中行最大 updated_at，用于 data_updated_at 一致性核对。 */
    public OffsetDateTime maxUpdatedAt(Connection connection, AggregateFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT max(updated_at) AS max_updated FROM ")
                .append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getObject("max_updated", OffsetDateTime.class);
            }
        } catch (SQLException e) {
            throw translate("Usage水位查询失败", e);
        }
    }

    /** 导出行：每行对应一个时间桶、分组值与币种组合（4.4.5）。 */
    public record ExportRow(
            OffsetDateTime bucketStart,
            OffsetDateTime bucketEnd,
            String dimensionValue,
            String currency,
            Map<String, String> dimensionNames,
            long requestCount,
            long successCount,
            long failureCount,
            long attemptCount,
            long initialCount,
            long retryCount,
            long credentialFailoverCount,
            long fallbackCount,
            long halfOpenProbeCount,
            long actualTokens,
            long estimatedTokens,
            long totalTokens,
            BigDecimal inputCost,
            BigDecimal outputCost,
            BigDecimal totalCost) {
    }

    /** 导出行数上限前置判断：桶×维度×币种组合计数，不加载明细。 */
    public long countExportRows(Connection connection, AggregateFilter filter,
                                String dimensionColumn) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM (SELECT 1 FROM ")
                .append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        sql.append(" GROUP BY bucket_start, bucket_end, ").append(dimensionColumn)
                .append(", currency) combinations");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw translate("导出计数失败", e);
        }
    }

    public List<ExportRow> exportRows(Connection connection, AggregateFilter filter,
                                      String dimensionColumn) {
        StringBuilder sql = new StringBuilder("SELECT bucket_start, bucket_end, ")
                .append(dimensionColumn).append(" AS dimension_value, currency, ")
                .append("max(dimension_names) AS dimension_names, ").append(SUM_COLUMNS)
                .append(", coalesce(sum(input_cost),0) AS input_cost, "
                        + "coalesce(sum(output_cost),0) AS output_cost, "
                        + "coalesce(sum(total_cost),0) AS total_cost FROM ")
                .append(qualified()).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, filter);
        sql.append(" GROUP BY bucket_start, bucket_end, ").append(dimensionColumn)
                .append(", currency ORDER BY bucket_start ASC, ").append(dimensionColumn)
                .append(" ASC, currency ASC");
        return queryList(connection, sql.toString(), params, rs -> {
            Map<String, String> names = namesFromJson(rs.getString("dimension_names"));
            long actual = rs.getLong("actual_input_tokens") + rs.getLong("actual_output_tokens");
            long estimated = rs.getLong("estimated_input_tokens") + rs.getLong("estimated_output_tokens");
            return new ExportRow(
                    rs.getObject("bucket_start", OffsetDateTime.class),
                    rs.getObject("bucket_end", OffsetDateTime.class),
                    rs.getString("dimension_value"), rs.getString("currency"), names,
                    rs.getLong("request_count"), rs.getLong("success_count"),
                    rs.getLong("failure_count"), rs.getLong("attempt_count"),
                    rs.getLong("initial_count"), rs.getLong("retry_count"),
                    rs.getLong("credential_failover_count"), rs.getLong("fallback_count"),
                    rs.getLong("half_open_probe_count"), actual, estimated,
                    rs.getLong("total_tokens"), rs.getBigDecimal("input_cost"),
                    rs.getBigDecimal("output_cost"), rs.getBigDecimal("total_cost"));
        });
    }


    public List<String> distinctCurrencies(Connection connection) {
        String sql = "SELECT DISTINCT currency FROM " + qualified() + " ORDER BY currency ASC";
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

    private void appendFilter(StringBuilder sql, List<Object> params, AggregateFilter filter) {
        if (filter == null) {
            return;
        }
        if (filter.granularity() != null && !filter.granularity().isBlank()) {
            sql.append(" AND granularity = ?");
            params.add(filter.granularity());
        }
        if (filter.rangeStart() != null) {
            sql.append(" AND bucket_start >= ?");
            params.add(filter.rangeStart());
        }
        if (filter.rangeEnd() != null) {
            sql.append(" AND bucket_end <= ?");
            params.add(filter.rangeEnd());
        }
        appendIn(sql, params, "application", filter.applications());
        appendIn(sql, params, "project", filter.projects());
        appendIn(sql, params, "tenant", filter.tenants());
        appendIn(sql, params, "alias_id", filter.aliasIds());
        appendIn(sql, params, "provider_id", filter.providerIds());
        appendIn(sql, params, "provider_model_id", filter.providerModelIds());
        appendIn(sql, params, "credential_pool_id", filter.credentialPoolIds());
        appendIn(sql, params, "credential_id", filter.credentialIds());
        appendIn(sql, params, "trace_status", filter.traceStatuses());
        appendIn(sql, params, "error_code", filter.errorCodes());
        appendIn(sql, params, "usage_source", filter.usageSources());
        if (filter.requestedStream() != null) {
            sql.append(" AND requested_stream = ?");
            params.add(filter.requestedStream());
        }
        if (filter.currency() != null && !filter.currency().isBlank()) {
            sql.append(" AND currency = ?");
            params.add(filter.currency());
        }
    }

    private static void appendIn(StringBuilder sql, List<Object> params, String column,
                                 List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(", ", java.util.Collections.nCopies(values.size(), "?")))
                .append(")");
        params.addAll(values);
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
            throw translate("Usage聚合查询失败", e);
        }
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private String toJson(Map<String, String> value) {
        if (value == null) {
            return "{}";
        }
        try {
            return com.lightai.client.json.ProtocolJson.protocol().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("dimension_names 序列化失败", e);
        }
    }

    private String toJsonNumberKeys(Map<String, Long> histogram) {
        if (histogram == null || histogram.isEmpty()) {
            return "{}";
        }
        try {
            return com.lightai.client.json.ProtocolJson.protocol().writeValueAsString(histogram);
        } catch (Exception e) {
            throw new IllegalStateException("直方图序列化失败", e);
        }
    }


    private static Map<String, String> namesFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return com.lightai.client.json.ProtocolJson.protocol().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.HashMap<String, String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("dimension_names 解析失败", e);
        }
    }

    private String qualified() {
        return schemaName + ".usage_aggregate";
    }

    private static IllegalStateException translate(String message, SQLException e) {
        return new IllegalStateException(message + "：" + e.getClass().getSimpleName(), e);
    }
}
