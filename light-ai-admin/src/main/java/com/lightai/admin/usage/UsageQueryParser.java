package com.lightai.admin.usage;

import com.lightai.admin.query.ListQuerySupport;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UsageQuery 解析与校验（BE-035；口径对齐 FE-034/035 附录 4.4.3）。
 * granularity=HOUR 最大跨度 31 天、DAY 最大 3650 天；多值筛选最多 20 项；
 * usage_source 仅 ACTUAL/ESTIMATED（MIXED 由 Trace 级汇总计算，不在聚合维度）；
 * TOTAL_COST 排序必须指定单一 currency；无权筛选字段由服务层判 ACCESS_DENIED，
 * 此处只做结构与取值校验。
 */
public final class UsageQueryParser {

    public static final int MAX_MULTI_VALUES = 20;
    public static final long HOUR_MAX_SPAN_DAYS = 31;
    public static final long DAY_MAX_SPAN_DAYS = 3650;

    public static final Set<String> TRACE_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED",
            "STREAM_INTERRUPTED", "RUNNING", "QUEUED");
    public static final Set<String> USAGE_SOURCES = Set.of("ACTUAL", "ESTIMATED");
    public static final Set<String> TREND_METRICS = Set.of("REQUEST_COUNT", "SUCCESS_RATE",
            "ATTEMPT_COUNT", "TOKEN", "COST", "RETRY", "CREDENTIAL_FAILOVER", "FALLBACK");
    public static final Set<String> GROUP_DIMENSIONS = Set.of("APPLICATION", "PROJECT", "TENANT",
            "ALIAS", "PROVIDER", "PROVIDER_MODEL", "CREDENTIAL_POOL", "CREDENTIAL",
            "TRACE_STATUS", "ERROR_CODE", "USAGE_SOURCE");
    /** 需要凭证查看权限的筛选/维度。 */
    public static final Set<String> CREDENTIAL_GATED_DIMENSIONS = Set.of("CREDENTIAL_POOL", "CREDENTIAL");

    public record UsageQuery(
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String granularity,
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
            String currency,
            String trendMetric,
            String groupBy,
            ListQuerySupport.ListQuery groupPage) {

        public boolean costSortRequested() {
            return groupPage != null && groupPage.sort() != null
                    && groupPage.sort().startsWith("total_cost");
        }
    }

    private UsageQueryParser() {
    }

    public static UsageQuery parse(Map<String, List<String>> params) {
        List<FieldIssue> issues = new ArrayList<>();

        OffsetDateTime startAt = parseTime(params, "start_at", issues);
        OffsetDateTime endAt = parseTime(params, "end_at", issues);
        if (startAt == null || endAt == null) {
            issues.add(new FieldIssue("start_at", "REQUIRED", "Usage 查询必须提供时间范围"));
        }
        String granularity = first(params, "granularity");
        if (granularity == null || granularity.isBlank()) {
            granularity = "DAY";
        }
        granularity = granularity.strip();
        if (!"HOUR".equals(granularity) && !"DAY".equals(granularity)) {
            issues.add(new FieldIssue("granularity", "INVALID", "granularity 仅支持 HOUR/DAY"));
        }
        if (startAt != null && endAt != null) {
            if (!startAt.isBefore(endAt)) {
                issues.add(new FieldIssue("start_at", "INVALID", "start_at 必须早于 end_at"));
            }
            long spanDays = Duration.between(startAt, endAt).toDays();
            if ("HOUR".equals(granularity) && spanDays > HOUR_MAX_SPAN_DAYS) {
                issues.add(new FieldIssue("granularity", "INVALID",
                        "HOUR 粒度最大跨度 " + HOUR_MAX_SPAN_DAYS + " 天"));
            }
            if ("DAY".equals(granularity) && spanDays > DAY_MAX_SPAN_DAYS) {
                issues.add(new FieldIssue("granularity", "INVALID",
                        "DAY 粒度最大跨度 " + DAY_MAX_SPAN_DAYS + " 天"));
            }
        }

        List<String> applications = multi(params, "application", null, issues);
        List<String> projects = multi(params, "project", null, issues);
        List<String> tenants = multi(params, "tenant", null, issues);
        List<String> aliasIds = multi(params, "alias_id", null, issues);
        List<String> providerIds = multi(params, "provider_id", null, issues);
        List<String> providerModelIds = multi(params, "provider_model_id", null, issues);
        List<String> credentialPoolIds = multi(params, "credential_pool_id", null, issues);
        List<String> credentialIds = multi(params, "credential_id", null, issues);
        List<String> traceStatuses = multi(params, "trace_status", TRACE_STATUSES, issues);
        List<String> errorCodes = multi(params, "error_code", null, issues);
        List<String> usageSources = multi(params, "usage_source", USAGE_SOURCES, issues);

        Boolean requestedStream = triState(params, "requested_stream", issues);

        String currency = first(params, "currency");
        if (currency != null && !currency.isBlank()) {
            currency = currency.strip();
            if (currency.length() != 3) {
                issues.add(new FieldIssue("currency", "INVALID", "currency 为 3 位币种代码"));
            }
        } else {
            currency = null;
        }

        String trendMetric = first(params, "trend_metric");
        if (trendMetric != null && !trendMetric.isBlank()
                && !TREND_METRICS.contains(trendMetric.strip())) {
            issues.add(new FieldIssue("trend_metric", "INVALID",
                    "trend_metric 含不支持的取值: " + trendMetric));
        }

        String groupBy = first(params, "group_by");
        if (groupBy == null || groupBy.isBlank()) {
            groupBy = "ALIAS";
        }
        groupBy = groupBy.strip();
        if (!GROUP_DIMENSIONS.contains(groupBy)) {
            issues.add(new FieldIssue("group_by", "INVALID",
                    "group_by 含不支持的取值: " + groupBy));
        }

        ListQuerySupport.ListQuery groupPage = ListQuerySupport.parse(first(params, "group_page"),
                first(params, "group_page_size"), first(params, "group_sort"),
                Set.of("request_count", "attempt_count", "total_tokens", "total_cost",
                        "dimension_name"),
                "total_cost desc");

        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "Usage查询参数不合法", issues);
        }

        return new UsageQuery(startAt, endAt, granularity, applications, projects, tenants,
                aliasIds, providerIds, providerModelIds, credentialPoolIds, credentialIds,
                traceStatuses, errorCodes, usageSources, requestedStream, currency,
                trendMetric == null ? null : trendMetric.strip(), groupBy, groupPage);
    }

    /** ListQuerySupport 白名单为 snake_case 数据列；分组排序枚举映射。 */
    private static Set<String> normalizedSortColumns() {
        return Set.of("REQUEST_COUNT", "ATTEMPT_COUNT", "TOTAL_TOKENS", "TOTAL_COST",
                "DIMENSION_NAME");
    }

    private static OffsetDateTime parseTime(Map<String, List<String>> params, String name,
                                            List<FieldIssue> issues) {
        String raw = first(params, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (Exception e) {
            issues.add(new FieldIssue(name, "INVALID", name + " 必须是 ISO-8601 时刻"));
            return null;
        }
    }

    private static List<String> multi(Map<String, List<String>> params, String name,
                                      Set<String> allowed, List<FieldIssue> issues) {
        List<String> raw = params.get(name);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Arrays.stream(entry.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                    .forEach(values::add);
        }
        if (values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_MULTI_VALUES) {
            issues.add(new FieldIssue(name, "INVALID", name + " 最多 " + MAX_MULTI_VALUES + " 项"));
            return List.of();
        }
        if (allowed != null) {
            for (String value : values) {
                if (!allowed.contains(value)) {
                    issues.add(new FieldIssue(name, "INVALID", name + " 含不支持的取值: " + value));
                    return List.of();
                }
            }
        }
        return List.copyOf(values);
    }

    private static Boolean triState(Map<String, List<String>> params, String name,
                                    List<FieldIssue> issues) {
        String raw = first(params, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        issues.add(new FieldIssue(name, "INVALID", name + " 仅支持 true/false"));
        return null;
    }

    private static String first(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** 控制器辅助：parameterMap → 多值 Map。 */
    public static Map<String, List<String>> toMultiMap(Map<String, String[]> parameterMap) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        parameterMap.forEach((name, values) -> {
            if (values != null && values.length > 0) {
                result.put(name, List.of(values));
            }
        });
        return result;
    }
}
