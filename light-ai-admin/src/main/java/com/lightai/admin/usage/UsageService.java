package com.lightai.admin.usage;

import com.lightai.admin.query.BucketAlignment;
import com.lightai.admin.usage.UsageQueryParser.UsageQuery;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.usage.UsageResults.AmountCost;
import com.lightai.client.usage.UsageResults.UsageGroupResult;
import com.lightai.client.usage.UsageResults.UsageGroupRow;
import com.lightai.client.usage.UsageResults.UsageSummaryResult;
import com.lightai.client.usage.UsageResults.UsageTrendPoint;
import com.lightai.client.usage.UsageResults.UsageTrendResult;
import com.lightai.storage.trace.JdbcObservationConfigReader;
import com.lightai.storage.trace.JdbcObservationConfigReader.ObservationConfig;
import com.lightai.storage.trace.JdbcUsageAggregateRepository.AggregateFilter;
import com.lightai.storage.trace.JdbcUsageAggregateRepository.BucketCurrencyCost;
import com.lightai.storage.trace.JdbcUsageAggregateRepository.BucketTotals;
import com.lightai.storage.trace.JdbcUsageAggregateRepository.CurrencyCost;
import com.lightai.storage.trace.JdbcUsageAggregateRepository.GroupRow;
import com.lightai.storage.trace.JdbcUsageAggregateRepository.UsageTotals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

/**
 * Usage 统一查询（BE-035；BACKEND_PLAN 4.4.4.2）。
 * summary/trends/groups 共用同一解析器、维度条件与桶归属，对同一组筛选字段返回相同
 * query_fingerprint，并回传解析对齐后的 start_at/end_at/timezone 供一致性核对。
 * 查询必须桶对齐：HOUR 按整点、DAY 按配置时区自然日；start_at 不得早于 usage_retention_days。
 * 多币种不产生跨币种总额（C-009）；TOTAL_COST 排序必须指定单一 currency；
 * 凭证维度筛选与分组需要凭证查看权限（C-012）。
 */
public class UsageService {

    private static final int DEFAULT_USAGE_RETENTION_DAYS = 365;
    private static final String UNKNOWN_DIMENSION_NAME = "未设置";

    /** 桶对齐后的查询口径；三接口与导出共用。 */
    public record ResolvedQuery(OffsetDateTime startAt, OffsetDateTime endAt, String timezone,
                                ZoneId zone, String fingerprint) {
    }

    private final DataSource dataSource;
    private final com.lightai.storage.trace.JdbcUsageAggregateRepository aggregateRepository;
    private final JdbcObservationConfigReader configReader;
    private final Clock clock;

    public UsageService(DataSource dataSource,
                        com.lightai.storage.trace.JdbcUsageAggregateRepository aggregateRepository,
                        JdbcObservationConfigReader configReader, Clock clock) {
        this.dataSource = dataSource;
        this.aggregateRepository = aggregateRepository;
        this.configReader = configReader;
        this.clock = clock;
    }

    public UsageSummaryResult summary(RequestContext context, Map<String, List<String>> params) {
        UsageQuery query = UsageQueryParser.parse(params);
        ResolvedQuery resolved = prepare(context, query);
        try (Connection connection = dataSource.getConnection()) {
            AggregateFilter filter = filterOf(resolved, query);
            UsageTotals totals = aggregateRepository.summarize(connection, filter);
            List<CurrencyCost> costs = aggregateRepository.costsByCurrency(connection, filter);
            OffsetDateTime dataUpdatedAt = aggregateRepository.maxUpdatedAt(connection, filter);

            BigDecimal inputCost = null;
            BigDecimal outputCost = null;
            BigDecimal totalCost = null;
            if (query.currency() != null) {
                for (CurrencyCost cost : costs) {
                    if (query.currency().equals(cost.currency())) {
                        inputCost = cost.inputCost();
                        outputCost = cost.outputCost();
                        totalCost = cost.totalCost();
                    }
                }
            }
            return new UsageSummaryResult(resolved.startAt(), resolved.endAt(), resolved.timezone(),
                    totals.requestCount(), totals.successCount(), totals.failureCount(),
                    totals.cancelledCount(), totals.queuedCount(), totals.streamCount(),
                    totals.streamInterruptedCount(),
                    rate(totals.successCount(),
                            totals.successCount() + totals.failureCount()
                                    + totals.streamInterruptedCount()),
                    totals.attemptCount(), totals.initialCount(), totals.retryCount(),
                    totals.credentialFailoverCount(), totals.fallbackCount(),
                    totals.halfOpenProbeCount(),
                    totals.inputTokens(), totals.outputTokens(), totals.totalTokens(),
                    totals.actualTokens(), totals.estimatedTokens(),
                    actualTokenRate(totals), inputCost, outputCost, totalCost,
                    toAmountCosts(costs),
                    dataUpdatedAt == null ? OffsetDateTime.now(clock) : dataUpdatedAt,
                    resolved.fingerprint());
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "Usage数据当前无法读取");
        }
    }

    public UsageTrendResult trends(RequestContext context, Map<String, List<String>> params) {
        UsageQuery query = UsageQueryParser.parse(params);
        ResolvedQuery resolved = prepare(context, query);
        try (Connection connection = dataSource.getConnection()) {
            AggregateFilter filter = filterOf(resolved, query);
            List<BucketTotals> buckets = aggregateRepository.trendBuckets(connection, filter);
            List<BucketCurrencyCost> bucketCosts =
                    aggregateRepository.costsByBucket(connection, filter);
            OffsetDateTime dataUpdatedAt = aggregateRepository.maxUpdatedAt(connection, filter);

            Map<OffsetDateTime, BucketTotals> bucketIndex = new LinkedHashMap<>();
            for (BucketTotals bucket : buckets) {
                bucketIndex.put(bucket.bucketStart(), bucket);
            }
            Map<OffsetDateTime, Map<String, BucketCurrencyCost>> costIndex = new LinkedHashMap<>();
            TreeSet<String> currencies = new TreeSet<>();
            for (BucketCurrencyCost cost : bucketCosts) {
                costIndex.computeIfAbsent(cost.bucketStart(), k -> new LinkedHashMap<>())
                        .put(cost.currency(), cost);
                currencies.add(cost.currency());
            }

            List<UsageTrendPoint> points = new ArrayList<>();
            for (OffsetDateTime bucketStart : iterateBuckets(resolved, query.granularity())) {
                BucketTotals bucket = bucketIndex.get(bucketStart);
                Map<String, BucketCurrencyCost> costsByCurrency =
                        costIndex.getOrDefault(bucketStart, Map.of());
                List<AmountCost> costs = new ArrayList<>();
                for (String currency : currencies) {
                    BucketCurrencyCost cost = costsByCurrency.get(currency);
                    costs.add(new AmountCost(currency,
                            cost == null ? BigDecimal.ZERO : cost.inputCost(),
                            cost == null ? BigDecimal.ZERO : cost.outputCost(),
                            cost == null ? BigDecimal.ZERO : cost.totalCost()));
                }
                points.add(toTrendPoint(bucket, bucketStart, resolved, query, costs));
            }
            return new UsageTrendResult(resolved.startAt(), resolved.endAt(), resolved.timezone(),
                    query.granularity(), List.copyOf(currencies),
                    dataUpdatedAt == null ? OffsetDateTime.now(clock) : dataUpdatedAt,
                    resolved.fingerprint(), List.copyOf(points));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "Usage数据当前无法读取");
        }
    }

    /** 缺失时间桶补零；成功率分母为 0 返回 null，不显示为真实 0（FE-032/034）。 */
    private UsageTrendPoint toTrendPoint(BucketTotals bucket, OffsetDateTime bucketStart,
                                         ResolvedQuery resolved, UsageQuery query,
                                         List<AmountCost> costs) {
        OffsetDateTime bucketEnd = bucketEnd(bucketStart, resolved.zone(), query.granularity());
        if (bucket == null) {
            return new UsageTrendPoint(bucketStart, bucketEnd, 0, 0, 0, null,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, costs);
        }
        return new UsageTrendPoint(bucketStart, bucketEnd,
                bucket.requestCount(), bucket.successCount(), bucket.failureCount(),
                rate(bucket.successCount(), bucket.successCount() + bucket.failureCount()
                        + bucket.streamInterruptedCount()),
                bucket.attemptCount(), bucket.initialCount(), bucket.retryCount(),
                bucket.credentialFailoverCount(), bucket.fallbackCount(),
                bucket.halfOpenProbeCount(),
                bucket.actualInputTokens() + bucket.actualOutputTokens(),
                bucket.estimatedInputTokens() + bucket.estimatedOutputTokens(),
                bucket.totalTokens(), costs);
    }

    public UsageGroupResult groups(RequestContext context, Map<String, List<String>> params) {
        UsageQuery query = UsageQueryParser.parse(params);
        if (UsageQueryParser.CREDENTIAL_GATED_DIMENSIONS.contains(query.groupBy())
                && !RequestPermissions.has(context, Permissions.CREDENTIAL_VIEW)) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED,
                    "无权按 " + query.groupBy() + " 分组");
        }
        if (query.costSortRequested() && query.currency() == null) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "TOTAL_COST 排序必须指定单一 currency",
                    List.of(new FieldIssue("currency", "REQUIRED",
                            "请求 total_cost 排序必须指定单一 currency")));
        }
        ResolvedQuery resolved = prepare(context, query);
        try (Connection connection = dataSource.getConnection()) {
            AggregateFilter filter = filterOf(resolved, query);
            List<GroupRow> rows = aggregateRepository.groupRows(connection, filter,
                    dimensionColumn(query.groupBy()));
            OffsetDateTime dataUpdatedAt = aggregateRepository.maxUpdatedAt(connection, filter);

            long totalRequest = 0;
            long totalTokens = 0;
            BigDecimal totalCost = BigDecimal.ZERO;
            for (GroupRow row : rows) {
                totalRequest += row.requestCount();
                totalTokens += row.totalTokens();
                totalCost = totalCost.add(nvl(row.totalCost()));
            }

            List<UsageGroupRow> merged = new ArrayList<>(rows.size());
            for (GroupRow row : rows) {
                merged.add(toGroupRow(query, row, totalRequest, totalTokens, totalCost));
            }
            merged.sort(sortOrder(query.groupPage().sort()));

            int from = (int) Math.min(query.groupPage().offset(), merged.size());
            int to = Math.min(from + query.groupPage().limit(), merged.size());
            return new UsageGroupResult(query.groupBy(), resolved.startAt(), resolved.endAt(),
                    resolved.timezone(), merged.size(), query.groupPage().page(),
                    query.groupPage().pageSize(),
                    dataUpdatedAt == null ? OffsetDateTime.now(clock) : dataUpdatedAt,
                    resolved.fingerprint(), List.copyOf(merged.subList(from, to)));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE, "Usage数据当前无法读取");
        }
    }

    /** 权限、保留期、桶对齐与 fingerprint 的公共前置。 */
    public ResolvedQuery prepare(RequestContext context, UsageQuery query) {
        RequestPermissions.require(context, Permissions.USAGE_VIEW);
        if ((!query.credentialPoolIds().isEmpty() || !query.credentialIds().isEmpty())
                && !RequestPermissions.has(context, Permissions.CREDENTIAL_VIEW)) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权使用凭证维度筛选");
        }

        ObservationConfig config;
        try (Connection connection = dataSource.getConnection()) {
            config = configReader.read(connection).orElse(null);
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE,
                    "运行参数当前无法读取");
        }
        ZoneId zone = safeZone(config == null ? null : config.timezone());
        int usageRetentionDays = config == null ? DEFAULT_USAGE_RETENTION_DAYS
                : config.usageRetentionDays();
        if (query.startAt().isBefore(OffsetDateTime.now(clock).minusDays(usageRetentionDays))) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "查询起点早于 Usage 保留范围",
                    List.of(new FieldIssue("start_at", "INVALID",
                            "start_at 不得早于 usage_retention_days 可查询范围")));
        }

        boolean hour = "HOUR".equals(query.granularity());
        OffsetDateTime alignedStart = BucketAlignment.alignStart(query.startAt(), zone, hour);
        OffsetDateTime alignedEnd = BucketAlignment.alignEnd(query.endAt(), zone, hour);
        String fingerprint = fingerprint(query, alignedStart, alignedEnd, zone);
        return new ResolvedQuery(alignedStart, alignedEnd, zone.getId(), zone, fingerprint);
    }

    /** fingerprint 覆盖四个接口共用的筛选字段；分页与展示字段不参与。 */
    static String fingerprint(UsageQuery query, OffsetDateTime startAt, OffsetDateTime endAt,
                              ZoneId zone) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("start_at=").append(startAt.toInstant());
        canonical.append("&end_at=").append(endAt.toInstant());
        canonical.append("&granularity=").append(query.granularity());
        canonical.append("&timezone=").append(zone.getId());
        appendMulti(canonical, "application", query.applications());
        appendMulti(canonical, "project", query.projects());
        appendMulti(canonical, "tenant", query.tenants());
        appendMulti(canonical, "alias_id", query.aliasIds());
        appendMulti(canonical, "provider_id", query.providerIds());
        appendMulti(canonical, "provider_model_id", query.providerModelIds());
        appendMulti(canonical, "credential_pool_id", query.credentialPoolIds());
        appendMulti(canonical, "credential_id", query.credentialIds());
        appendMulti(canonical, "trace_status", query.traceStatuses());
        appendMulti(canonical, "error_code", query.errorCodes());
        appendMulti(canonical, "usage_source", query.usageSources());
        canonical.append("&requested_stream=").append(query.requestedStream());
        canonical.append("&currency=").append(query.currency() == null ? "" : query.currency());
        return ContributionCalculator.sha256Hex(canonical.toString());
    }

    private static void appendMulti(StringBuilder canonical, String name, List<String> values) {
        canonical.append('&').append(name).append('=');
        if (values == null || values.isEmpty()) {
            return;
        }
        canonical.append(String.join(",", values.stream().sorted().toList()));
    }

    private List<OffsetDateTime> iterateBuckets(ResolvedQuery resolved, String granularity) {
        return BucketAlignment.iterateBuckets(resolved.startAt(), resolved.endAt(),
                resolved.zone(), "HOUR".equals(granularity));
    }

    private static OffsetDateTime bucketEnd(OffsetDateTime bucketStart, ZoneId zone,
                                            String granularity) {
        return BucketAlignment.bucketEnd(bucketStart, zone, "HOUR".equals(granularity));
    }

    private AggregateFilter filterOf(ResolvedQuery resolved, UsageQuery query) {
        return new AggregateFilter(query.granularity(), resolved.startAt(), resolved.endAt(),
                query.applications(), query.projects(), query.tenants(), query.aliasIds(),
                query.providerIds(), query.providerModelIds(), query.credentialPoolIds(),
                query.credentialIds(), query.traceStatuses(), query.errorCodes(),
                query.usageSources(), query.requestedStream(), query.currency());
    }

    static String dimensionColumn(String dimensionType) {
        return switch (dimensionType) {
            case "APPLICATION" -> "application";
            case "PROJECT" -> "project";
            case "TENANT" -> "tenant";
            case "ALIAS" -> "alias_id";
            case "PROVIDER" -> "provider_id";
            case "PROVIDER_MODEL" -> "provider_model_id";
            case "CREDENTIAL_POOL" -> "credential_pool_id";
            case "CREDENTIAL" -> "credential_id";
            case "TRACE_STATUS" -> "trace_status";
            case "ERROR_CODE" -> "error_code";
            case "USAGE_SOURCE" -> "usage_source";
            default -> throw new IllegalArgumentException("未知分组维度: " + dimensionType);
        };
    }

    private static final Map<String, String> DIMENSION_NAME_KEYS = Map.of(
            "ALIAS", "alias", "PROVIDER", "provider", "PROVIDER_MODEL", "provider_model",
            "CREDENTIAL_POOL", "credential_pool", "CREDENTIAL", "credential");

    private UsageGroupRow toGroupRow(UsageQuery query, GroupRow row, long totalRequest,
                                     long totalTokens, BigDecimal totalCost) {
        String nameKey = DIMENSION_NAME_KEYS.get(query.groupBy());
        String name;
        if (nameKey == null) {
            name = row.dimensionValue() == null || row.dimensionValue().isBlank()
                    ? UNKNOWN_DIMENSION_NAME : row.dimensionValue();
        } else {
            String snapshot = row.dimensionNames() == null ? null
                    : row.dimensionNames().get(nameKey);
            name = snapshot == null || snapshot.isBlank() ? UNKNOWN_DIMENSION_NAME : snapshot;
        }
        boolean singleCurrency = query.currency() != null;
        long successDenominator = row.successCount() + row.failureCount()
                + row.streamInterruptedCount();
        return new UsageGroupRow(query.groupBy(), row.dimensionValue(), name, row.currency(),
                row.requestCount(), row.successCount(), row.failureCount(),
                rate(row.successCount(), successDenominator),
                row.attemptCount(), row.initialCount(), row.retryCount(),
                row.credentialFailoverCount(), row.fallbackCount(), row.halfOpenProbeCount(),
                row.actualInputTokens() + row.actualOutputTokens(),
                row.estimatedInputTokens() + row.estimatedOutputTokens(),
                row.totalTokens(), row.inputCost(), row.outputCost(), row.totalCost(),
                share(row.requestCount(), totalRequest),
                share(row.totalTokens(), totalTokens),
                singleCurrency ? share(nvl(row.totalCost()), totalCost) : null);
    }

    /** 百分比份额 0—100（4 位 HALF_UP）；分母为 0 返回 null。 */
    static BigDecimal share(long value, long total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(value * 100L)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    /** 金额份额：定点金额参与分摊，不做浮点。 */
    static BigDecimal share(BigDecimal value, BigDecimal total) {
        if (total == null || total.signum() <= 0) {
            return null;
        }
        return nvl(value).multiply(BigDecimal.valueOf(100L))
                .divide(total, 4, RoundingMode.HALF_UP);
    }

    static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator * 100L)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    static BigDecimal actualTokenRate(UsageTotals totals) {
        long actual = totals.actualTokens();
        long total = actual + totals.estimatedTokens();
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(actual * 100L)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private Comparator<UsageGroupRow> sortOrder(String sortExpression) {
        String[] parts = sortExpression.split("\\s+");
        String column = parts[0];
        boolean desc = parts.length > 1 && "desc".equals(parts[1]);
        Comparator<UsageGroupRow> comparator = switch (column) {
            case "request_count" -> Comparator.comparingLong(UsageGroupRow::requestCount);
            case "attempt_count" -> Comparator.comparingLong(UsageGroupRow::attemptCount);
            case "total_tokens" -> Comparator.comparingLong(UsageGroupRow::totalTokens);
            case "total_cost" -> Comparator.comparing(UsageGroupRow::totalCost,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            case "dimension_name" -> Comparator.comparing(UsageGroupRow::dimensionName,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(UsageGroupRow::dimensionId,
                            Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(UsageGroupRow::dimensionId,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        return desc ? comparator.reversed() : comparator;
    }

    private static List<AmountCost> toAmountCosts(List<CurrencyCost> costs) {
        List<AmountCost> result = new ArrayList<>(costs.size());
        for (CurrencyCost cost : costs) {
            result.add(new AmountCost(cost.currency(), cost.inputCost(), cost.outputCost(),
                    cost.totalCost()));
        }
        return List.copyOf(result);
    }

    private static ZoneId safeZone(String timezone) {
        return BucketAlignment.safeZone(timezone);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
