package com.lightai.admin.overview;

import com.lightai.admin.overview.OverviewQueryParser.OverviewQuery;
import com.lightai.admin.query.BucketAlignment;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.client.overview.OverviewResults.AmountByCurrency;
import com.lightai.client.overview.OverviewResults.OverviewExceptionItem;
import com.lightai.client.overview.OverviewResults.OverviewExceptionResult;
import com.lightai.client.overview.OverviewResults.OverviewExceptionSummary;
import com.lightai.client.overview.OverviewResults.OverviewFilterOptions;
import com.lightai.client.overview.OverviewResults.OverviewOptionRef;
import com.lightai.client.overview.OverviewResults.OverviewSummary;
import com.lightai.client.overview.OverviewResults.OverviewTrendPoint;
import com.lightai.client.overview.OverviewResults.OverviewTrendResult;
import com.lightai.storage.trace.JdbcObservationConfigReader;
import com.lightai.storage.trace.JdbcObservationConfigReader.ObservationConfig;
import com.lightai.storage.trace.JdbcOverviewStatsRepository.BucketCurrencyAmount;
import com.lightai.storage.trace.JdbcOverviewStatsRepository.BucketTraceTotals;
import com.lightai.storage.trace.JdbcOverviewStatsRepository.CurrencyAmount;
import com.lightai.storage.trace.JdbcOverviewStatsRepository.FailureTraceItem;
import com.lightai.storage.trace.JdbcOverviewStatsRepository.OverviewFilter;
import com.lightai.storage.trace.JdbcOverviewStatsRepository.TraceTotals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 运行概览（BE-034；BACKEND_PLAN 4.4.4 与 FE-031/032/033）。
 * 请求状态来自 Trace 同范围快照；成功率先注入数据范围再聚合；
 * 多币种不做跨币种总额（C-009）；Credential 异常项只对具备凭证查看权限的角色返回（C-012）。
 * 异常列表优先级：OPEN 熔断、HALF_OPEN 熔断、不可用候选、无效凭证、失败 Trace，
 * 同级按 occurrence_count 与 latest_at 排序，默认最多 20 项。
 */
public class OverviewService {

    private static final int EXCEPTION_ITEM_LIMIT = 20;
    private static final int FILTER_APPLICATION_LIMIT = 50;
    private static final int DEFAULT_TRACE_RETENTION_DAYS = 30;
    private static final int DEFAULT_USAGE_RETENTION_DAYS = 365;

    private final DataSource dataSource;
    private final com.lightai.storage.trace.JdbcOverviewStatsRepository statsRepository;
    private final JdbcObservationConfigReader configReader;
    private final Clock clock;

    public OverviewService(DataSource dataSource,
                           com.lightai.storage.trace.JdbcOverviewStatsRepository statsRepository,
                           JdbcObservationConfigReader configReader, Clock clock) {
        this.dataSource = dataSource;
        this.statsRepository = statsRepository;
        this.configReader = configReader;
        this.clock = clock;
    }

    public OverviewFilterOptions filters(RequestContext context, String aliasId) {
        RequestPermissions.require(context, Permissions.OVERVIEW_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            List<String> scope = scopeOf(context);
            List<String> applications = scope.isEmpty()
                    ? statsRepository.distinctApplications(connection, FILTER_APPLICATION_LIMIT)
                    : List.copyOf(scope);
            UUID alias = parseUuid(aliasId);
            List<OverviewOptionRef> aliases = toRefs(statsRepository.aliasOptions(connection));
            List<OverviewOptionRef> providers = alias == null
                    ? toRefs(statsRepository.providerOptions(connection))
                    : toRefs(statsRepository.providerOptionsByAlias(connection, alias));
            List<String> currencies = statsRepository.distinctUsageCurrencies(connection);
            return new OverviewFilterOptions(applications, aliases, providers, currencies);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "概览筛选当前无法读取");
        }
    }

    public OverviewSummary summary(RequestContext context, Map<String, List<String>> params) {
        RequestPermissions.require(context, Permissions.OVERVIEW_VIEW);
        OverviewQuery query = OverviewQueryParser.parse(params, false);
        boolean credentialFields = RequestPermissions.has(context, Permissions.CREDENTIAL_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            OverviewFilter filter = filterOf(connection, context, query, clock);
            TraceTotals totals = statsRepository.summary(connection, filter);
            List<CurrencyAmount> costs = statsRepository.costsByCurrency(connection, filter);
            long openCircuits = statsRepository.countCircuitsByState(connection, "OPEN");
            long unavailableCandidates = statsRepository.countUnavailableCandidates(connection);
            Long invalidCredentials = credentialFields
                    ? statsRepository.countInvalidCredentials(connection) : null;
            long denominator = totals.successCount() + totals.failureCount()
                    + totals.streamInterruptedCount();
            return new OverviewSummary(
                    totals.requestCount(), totals.successCount(), totals.failureCount(),
                    totals.streamInterruptedCount(), totals.cancelledCount(), totals.activeCount(),
                    rate(totals.successCount(), denominator),
                    totals.averageTotalMs(), totals.p95FirstTokenMs(),
                    totals.totalTokens(), totals.actualTokens(), totals.estimatedTokens(),
                    toAmounts(costs), totals.retryCount(), totals.credentialFailoverCount(),
                    totals.fallbackCount(), openCircuits, unavailableCandidates,
                    invalidCredentials, OffsetDateTime.now(clock));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE,
                    "概览摘要当前无法读取");
        }
    }

    public OverviewTrendResult trends(RequestContext context, Map<String, List<String>> params) {
        RequestPermissions.require(context, Permissions.OVERVIEW_VIEW);
        OverviewQuery query = OverviewQueryParser.parse(params, true);
        try (Connection connection = dataSource.getConnection()) {
            OverviewFilter filter = filterOf(connection, context, query, clock);
            ZoneId zone = timezone(connection);
            boolean hour = "HOUR".equals(query.granularity());
            List<BucketTraceTotals> buckets = statsRepository.trendBuckets(connection, filter,
                    hour ? "hour" : "day", zone.getId());
            List<BucketCurrencyAmount> bucketCosts = statsRepository.costsByBucket(connection,
                    filter, hour ? "hour" : "day", zone.getId());

            Map<OffsetDateTime, BucketTraceTotals> bucketIndex = new LinkedHashMap<>();
            for (BucketTraceTotals bucket : buckets) {
                bucketIndex.put(bucket.bucketStart(), bucket);
            }
            Map<OffsetDateTime, Map<String, BucketCurrencyAmount>> costIndex = new LinkedHashMap<>();
            TreeSet<String> currencies = new TreeSet<>();
            for (BucketCurrencyAmount cost : bucketCosts) {
                costIndex.computeIfAbsent(cost.bucketStart(), k -> new LinkedHashMap<>())
                        .put(cost.currency(), cost);
                currencies.add(cost.currency());
            }

            List<OverviewTrendPoint> points = new ArrayList<>();
            List<OffsetDateTime> aligned = BucketAlignment.iterateBuckets(
                    BucketAlignment.alignStart(query.startAt(), zone, hour),
                    BucketAlignment.alignEnd(query.endAt(), zone, hour), zone, hour);
            for (OffsetDateTime bucketStart : aligned) {
                BucketTraceTotals bucket = bucketIndex.get(bucketStart);
                Map<String, BucketCurrencyAmount> currencyCosts =
                        costIndex.getOrDefault(bucketStart, Map.of());
                List<AmountByCurrency> costs = new ArrayList<>();
                for (String currency : currencies) {
                    BucketCurrencyAmount amount = currencyCosts.get(currency);
                    costs.add(new AmountByCurrency(currency,
                            amount == null ? BigDecimal.ZERO : amount.totalCost()));
                }
                points.add(toTrendPoint(bucket, bucketStart, zone, hour, costs));
            }
            return new OverviewTrendResult(OffsetDateTime.now(clock), query.granularity(),
                    List.copyOf(currencies), List.copyOf(points));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE,
                    "概览趋势当前无法读取");
        }
    }

    /** 无数据桶补零；成功率分母 0 与无流式样本的 P95 返回 null（FE-032）。 */
    private OverviewTrendPoint toTrendPoint(BucketTraceTotals bucket, OffsetDateTime bucketStart,
                                            ZoneId zone, boolean hour,
                                            List<AmountByCurrency> costs) {
        OffsetDateTime bucketEnd = BucketAlignment.bucketEnd(bucketStart, zone, hour);
        if (bucket == null) {
            return new OverviewTrendPoint(bucketStart, bucketEnd, 0, 0, 0, null, null, null,
                    0, 0, 0, costs);
        }
        return new OverviewTrendPoint(bucketStart, bucketEnd, bucket.requestCount(),
                bucket.successCount(), bucket.failureCount(),
                rate(bucket.successCount(), bucket.successCount() + bucket.failureCount()),
                bucket.averageTotalMs(), bucket.p95FirstTokenMs(), bucket.totalTokens(),
                bucket.retryCount(), bucket.fallbackCount(), costs);
    }

    public OverviewExceptionResult exceptions(RequestContext context,
                                              Map<String, List<String>> params) {
        RequestPermissions.require(context, Permissions.OVERVIEW_VIEW);
        OverviewQuery query = OverviewQueryParser.parse(params, false);
        boolean credentialFields = RequestPermissions.has(context, Permissions.CREDENTIAL_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            OverviewFilter filter = filterOf(connection, context, query, clock);

            long openCircuits = statsRepository.countCircuitsByState(connection, "OPEN");
            long halfOpenCircuits = statsRepository.countCircuitsByState(connection, "HALF_OPEN");
            long unavailableCandidates = statsRepository.countUnavailableCandidates(connection);
            long invalidCredentials = credentialFields
                    ? statsRepository.countInvalidCredentials(connection) : -1;
            long recentFailures = statsRepository.countFailureTraces(connection, filter);

            OverviewExceptionSummary summary = new OverviewExceptionSummary(openCircuits,
                    halfOpenCircuits, unavailableCandidates,
                    invalidCredentials >= 0 ? invalidCredentials : null, recentFailures);

            List<OverviewExceptionItem> items = new ArrayList<>();
            if (openCircuits + halfOpenCircuits > 0) {
                statsRepository.circuitItems(connection).forEach(circuit ->
                        items.add(new OverviewExceptionItem("CIRCUIT", circuit.id().toString(),
                                circuit.modelName() == null ? "circuit" : circuit.modelName(),
                                circuit.state(), null, circuit.lastReason(),
                                circuit.occurrenceCount(), circuit.latestAt(),
                                circuit.providerName(), circuit.modelName(), null)));
            }
            if (unavailableCandidates > 0) {
                statsRepository.unavailableCandidateItems(connection).forEach(candidate ->
                        items.add(new OverviewExceptionItem("CANDIDATE", candidate.id().toString(),
                                candidate.modelName() == null ? "candidate" : candidate.modelName(),
                                "UNAVAILABLE", null, null, 1, candidate.latestAt(),
                                candidate.providerName(), candidate.modelName(),
                                candidate.aliasName())));
            }
            if (credentialFields && invalidCredentials > 0) {
                statsRepository.invalidCredentialItems(connection).forEach(credential ->
                        items.add(new OverviewExceptionItem("CREDENTIAL", credential.id().toString(),
                                credential.name(), "INVALID", null, credential.lastReason(),
                                1, credential.latestAt(), credential.providerName(), null, null)));
            }
            if (recentFailures > 0) {
                statsRepository.failureTraceItems(connection, filter, EXCEPTION_ITEM_LIMIT)
                        .forEach(trace -> items.add(new OverviewExceptionItem("TRACE",
                                trace.traceId(), trace.alias() == null ? "trace" : trace.alias(),
                                trace.status(), trace.errorCode(), trace.errorSummary(), 1,
                                trace.latestAt(), null, null, trace.alias())));
            }

            items.sort(Comparator
                    .comparingInt((OverviewExceptionItem item) -> switch (item.itemType()) {
                        case "CIRCUIT" -> "OPEN".equals(item.status()) ? 0 : 1;
                        case "CANDIDATE" -> 2;
                        case "CREDENTIAL" -> 3;
                        default -> 4;
                    })
                    .thenComparing(Comparator
                            .comparingLong(OverviewExceptionItem::occurrenceCount).reversed())
                    .thenComparing(Comparator.comparing(OverviewExceptionItem::latestAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))));
            List<OverviewExceptionItem> limited =
                    items.size() > EXCEPTION_ITEM_LIMIT ? items.subList(0, EXCEPTION_ITEM_LIMIT) : items;
            return new OverviewExceptionResult(OffsetDateTime.now(clock), summary,
                    List.copyOf(limited));
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE,
                    "概览异常当前无法读取");
        }
    }

    private OverviewFilter filterOf(Connection connection, RequestContext context,
                                    OverviewQuery query, Clock clock) {
        List<String> scope = scopeOf(context);
        String application = query.application();
        if (application != null && !scope.isEmpty() && !scope.contains(application)) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权查询该应用数据");
        }
        List<String> applications = scope.isEmpty()
                ? (application == null ? List.of() : List.of(application))
                : (application == null ? scope : List.of(application));
        UUID alias = parseUuid(query.aliasId());
        UUID provider = parseUuid(query.providerId());
        return new OverviewFilter(query.startAt(), query.endAt(), applications, alias, provider);
    }

    private static List<String> scopeOf(RequestContext context) {
        return context == null || context.authContext() == null
                ? List.of() : context.authContext().applicationScope();
    }

    private ZoneId timezone(Connection connection) {
        ObservationConfig config = configReader.read(connection).orElse(null);
        return BucketAlignment.safeZone(config == null ? null : config.timezone());
    }

    private static List<OverviewOptionRef> toRefs(List<com.lightai.storage.trace.JdbcOverviewStatsRepository.OptionRef> options) {
        List<OverviewOptionRef> refs = new ArrayList<>(options.size());
        for (var option : options) {
            refs.add(new OverviewOptionRef(option.id().toString(), option.name()));
        }
        return List.copyOf(refs);
    }

    private static List<AmountByCurrency> toAmounts(List<CurrencyAmount> costs) {
        List<AmountByCurrency> amounts = new ArrayList<>(costs.size());
        for (CurrencyAmount cost : costs) {
            amounts.add(new AmountByCurrency(cost.currency(), cost.totalCost()));
        }
        return List.copyOf(amounts);
    }

    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator * 100L)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return UUID.fromString(raw);
    }
}
