package com.lightai.admin.trace;

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
 * TraceListQuery 解析与校验（BE-031；口径对齐 FE-025 附录 4.4.1.2）。
 * 普通组合查询必填时间范围、跨度最大 31 天；trace_id 精确查询忽略业务筛选与分页；
 * 多值筛选最多 20 项；枚举与三态开关严格校验，非法输入返回 FIELD_VALIDATION_FAILED。
 */
public final class TraceListQueryParser {

    public static final int MAX_SPAN_DAYS = 31;
    public static final int MAX_MULTI_VALUES = 20;
    public static final long MIN_TOTAL_MS_LIMIT = 600000L;

    public static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "SUCCEEDED",
            "FAILED", "CANCELLED", "STREAM_INTERRUPTED");
    public static final Set<String> SOURCE_MODES = Set.of("LOCAL_RUNTIME", "EMBEDDED", "STANDALONE_SERVER");
    public static final Set<String> ATTEMPT_TYPES = Set.of("INITIAL", "RETRY", "CREDENTIAL_FAILOVER",
            "FALLBACK", "HALF_OPEN_PROBE");
    public static final Set<String> USAGE_SOURCES = Set.of("ACTUAL", "ESTIMATED", "MIXED");

    public static final Set<String> SORTABLE_COLUMNS =
            Set.of("started_at", "total_ms", "total_tokens", "total_cost");

    /** 归一化查询；时间范围已解析为 UTC 时刻，多值列表保持输入顺序。 */
    public record TraceListQuery(
            String exactTraceId,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            List<String> applications,
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
            Boolean anomalousRunning,
            ListQuerySupport.ListQuery page) {

        public boolean exactId() {
            return exactTraceId != null && !exactTraceId.isBlank();
        }
    }

    private TraceListQueryParser() {
    }

    /**
     * @param params 多值查询参数（repeated key 或逗号分隔均可）
     */
    public static TraceListQuery parse(Map<String, List<String>> params) {
        List<FieldIssue> issues = new ArrayList<>();

        String traceId = first(params, "trace_id");
        boolean exact = traceId != null && !traceId.isBlank();
        if (exact && (traceId.length() < 1 || traceId.length() > 128)) {
            issues.add(new FieldIssue("trace_id", "INVALID", "trace_id 长度 1—128"));
        }

        OffsetDateTime startAt = parseTime(params, "start_at", issues);
        OffsetDateTime endAt = parseTime(params, "end_at", issues);
        if (!exact) {
            if (startAt == null || endAt == null) {
                issues.add(new FieldIssue("start_at", "REQUIRED", "普通组合查询必须提供时间范围"));
            } else {
                if (!startAt.isBefore(endAt)) {
                    issues.add(new FieldIssue("start_at", "INVALID", "start_at 必须早于 end_at"));
                }
                if (Duration.between(startAt, endAt).toDays() > MAX_SPAN_DAYS) {
                    issues.add(new FieldIssue("start_at", "INVALID",
                            "时间跨度最大 " + MAX_SPAN_DAYS + " 天"));
                }
            }
        }

        List<String> applications = multi(params, "application", issues);
        List<String> aliasIds = multi(params, "alias_id", issues);
        List<String> providerIds = multi(params, "provider_id", issues);
        List<String> providerModelIds = multi(params, "provider_model_id", issues);
        List<String> statuses = multiEnum(params, "status", STATUSES, issues);
        List<String> projects = multi(params, "project", issues);
        List<String> tenants = multi(params, "tenant", issues);
        List<String> sourceModes = multiEnum(params, "source_mode", SOURCE_MODES, issues);
        List<String> accessCredentialIds = multi(params, "access_credential_id", issues);
        List<String> credentialIds = multi(params, "credential_id", issues);
        List<String> attemptTypes = multiEnum(params, "attempt_type", ATTEMPT_TYPES, issues);
        List<String> errorCodes = multi(params, "error_code", issues);
        List<String> usageSources = multiEnum(params, "usage_source", USAGE_SOURCES, issues);

        String tagKey = first(params, "tag_key");
        String tagValue = first(params, "tag_value");
        boolean hasKey = tagKey != null && !tagKey.isBlank();
        boolean hasValue = tagValue != null && !tagValue.isBlank();
        if (hasKey != hasValue) {
            issues.add(new FieldIssue("tag_key", "REQUIRED", "tag_key 与 tag_value 必须成对提供"));
        }
        if (hasKey && tagKey.length() > 64) {
            issues.add(new FieldIssue("tag_key", "INVALID", "tag_key 最长 64 字符"));
        }
        if (hasValue && tagValue.length() > 256) {
            issues.add(new FieldIssue("tag_value", "INVALID", "tag_value 最长 256 字符"));
        }

        String requestUser = text(params, "request_user", 128, issues);
        String clientIp = text(params, "client_ip", 64, issues);

        Boolean requestedStream = triState(params, "requested_stream", issues);
        Boolean hasRetry = triState(params, "has_retry", issues);
        Boolean hasCredentialFailover = triState(params, "has_credential_failover", issues);
        Boolean hasFallback = triState(params, "has_fallback", issues);
        Boolean anomalousRunning = triState(params, "anomalous_running", issues);

        Long minTotalMs = parseMs(params, "min_total_ms", issues);
        Long maxTotalMs = parseMs(params, "max_total_ms", issues);
        if (minTotalMs != null && maxTotalMs != null && maxTotalMs < minTotalMs) {
            issues.add(new FieldIssue("max_total_ms", "INVALID",
                    "max_total_ms 必须大于等于 min_total_ms"));
        }

        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "Trace查询参数不合法", issues);
        }

        ListQuerySupport.ListQuery page = exact
                ? new ListQuerySupport.ListQuery(1, ListQuerySupport.DEFAULT_PAGE_SIZE, "started_at desc")
                : ListQuerySupport.parse(first(params, "page"), first(params, "page_size"),
                        first(params, "sort"), SORTABLE_COLUMNS, "started_at desc");

        if (exact) {
            // 精确 trace_id 分支：忽略时间范围与业务筛选（4.4.1.2），仍保留合法性校验
            return new TraceListQuery(traceId, null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    null, null, List.of(), List.of(), List.of(), null, null,
                    List.of(), List.of(), null, List.of(),
                    null, null, null, null, null, null, page);
        }

        return new TraceListQuery(
                null, startAt, endAt,
                applications, aliasIds, providerIds, providerModelIds, statuses, projects, tenants,
                hasKey ? tagKey.strip() : null, hasValue ? tagValue.strip() : null,
                sourceModes, accessCredentialIds, credentialIds, requestUser, clientIp,
                attemptTypes, errorCodes, requestedStream, usageSources,
                hasRetry, hasCredentialFailover, hasFallback, minTotalMs, maxTotalMs,
                anomalousRunning, page);
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
                                      List<FieldIssue> issues) {
        return multiRaw(params, name, null, issues);
    }

    private static List<String> multiEnum(Map<String, List<String>> params, String name,
                                          Set<String> allowed, List<FieldIssue> issues) {
        return multiRaw(params, name, allowed, issues);
    }

    private static List<String> multiRaw(Map<String, List<String>> params, String name,
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
                    issues.add(new FieldIssue(name, "INVALID",
                            name + " 含不支持的取值: " + value));
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

    private static Long parseMs(Map<String, List<String>> params, String name,
                                List<FieldIssue> issues) {
        String raw = first(params, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.strip());
            if (value < 0 || value > MIN_TOTAL_MS_LIMIT) {
                issues.add(new FieldIssue(name, "INVALID",
                        name + " 范围 0—" + MIN_TOTAL_MS_LIMIT));
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            issues.add(new FieldIssue(name, "INVALID", name + " 必须是非负整数"));
            return null;
        }
    }

    private static String text(Map<String, List<String>> params, String name, int maxLength,
                               List<FieldIssue> issues) {
        String raw = first(params, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > maxLength) {
            issues.add(new FieldIssue(name, "INVALID", name + " 最长 " + maxLength + " 字符"));
            return null;
        }
        return value;
    }

    private static String first(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** 控制器辅助：把 parameterMap 转为多值 Map，保持参数出现顺序。 */
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
