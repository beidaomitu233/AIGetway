package com.lightai.admin.overview;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OverviewQuery 解析与校验（BE-034；口径对齐 FE-031/032/033 附录 4.1）。
 * 起止相等、开始晚于结束或跨度超 365 天禁止查询；application 为单选，
 * 开发人员等受限身份只能选择 application_scope 内的值（服务层注入校验）。
 * summary/exceptions 不使用 granularity；exceptions 不使用 currency。
 */
public final class OverviewQueryParser {

    public static final long MAX_SPAN_DAYS = 365;
    public static final long HOUR_MAX_SPAN_DAYS = 31;
    private static final Set<String> GRANULARITIES = Set.of("HOUR", "DAY");

    /** @param application 为受限身份注入后的最终值；scope 语义见服务层 */
    public record OverviewQuery(
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String application,
            String aliasId,
            String providerId,
            String currency,
            String granularity) {
    }

    private OverviewQueryParser() {
    }

    /** @param requireGranularity trends 为 true；summary/exceptions 传 false */
    public static OverviewQuery parse(Map<String, List<String>> params,
                                      boolean requireGranularity) {
        List<FieldIssue> issues = new ArrayList<>();

        OffsetDateTime startAt = parseTime(params, "start_at", issues);
        OffsetDateTime endAt = parseTime(params, "end_at", issues);
        if (startAt == null || endAt == null) {
            issues.add(new FieldIssue("start_at", "REQUIRED", "概览查询必须提供时间范围"));
        } else {
            if (!startAt.isBefore(endAt)) {
                issues.add(new FieldIssue("start_at", "INVALID",
                        "起止相等或开始晚于结束，禁止查询"));
            }
            if (Duration.between(startAt, endAt).toDays() > MAX_SPAN_DAYS) {
                issues.add(new FieldIssue("start_at", "INVALID",
                        "时间跨度最大 " + MAX_SPAN_DAYS + " 天"));
            }
        }

        String application = first(params, "application");
        String aliasId = uuid(params, "alias_id", issues);
        String providerId = uuid(params, "provider_id", issues);
        String currency = first(params, "currency");
        if (currency != null && currency.isBlank()) {
            currency = null;
        }

        String granularity = first(params, "granularity");
        if (requireGranularity) {
            if (granularity == null || granularity.isBlank()) {
                issues.add(new FieldIssue("granularity", "REQUIRED", "趋势查询必须提供 granularity"));
            } else {
                granularity = granularity.strip();
                if (!GRANULARITIES.contains(granularity)) {
                    issues.add(new FieldIssue("granularity", "INVALID",
                            "granularity 仅支持 HOUR/DAY"));
                } else if (startAt != null && endAt != null
                        && "HOUR".equals(granularity)
                        && Duration.between(startAt, endAt).toDays() > HOUR_MAX_SPAN_DAYS) {
                    issues.add(new FieldIssue("granularity", "INVALID",
                            "HOUR 粒度最大跨度 " + HOUR_MAX_SPAN_DAYS + " 天"));
                }
            }
        } else {
            granularity = null;
            if (currency != null) {
                // exceptions 不使用 currency；summary 忽略 granularity 由调用方约束
            }
        }

        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "概览查询参数不合法", issues);
        }
        return new OverviewQuery(startAt, endAt, application, aliasId, providerId, currency,
                granularity);
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

    private static String uuid(Map<String, List<String>> params, String name,
                               List<FieldIssue> issues) {
        String raw = first(params, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip()).toString();
        } catch (IllegalArgumentException e) {
            issues.add(new FieldIssue(name, "INVALID", name + " 必须是 UUID"));
            return null;
        }
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
