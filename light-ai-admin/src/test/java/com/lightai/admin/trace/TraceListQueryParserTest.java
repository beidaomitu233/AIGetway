package com.lightai.admin.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BE-031 组合查询解析测试：31 天边界、精确 ID 分支、多值上限、枚举与三态校验。
 */
class TraceListQueryParserTest {

    private static OffsetDateTime at(String iso) {
        return OffsetDateTime.parse(iso);
    }

    private Map<String, List<String>> params(Object... keyValues) {
        Map<String, List<String>> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> value = (List<String>) keyValues[i + 1];
            params.put((String) keyValues[i], value);
        }
        return params;
    }

    @Test
    void normalQueryRequiresRangeAndRejectsSpanOver31Days() {
        Map<String, List<String>> params = params(
                "start_at", List.of("2026-09-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"));
        TraceListQueryParser.TraceListQuery query = TraceListQueryParser.parse(params);
        assertThat(query.exactId()).isFalse();
        assertThat(query.startAt()).isEqualTo(at("2026-09-01T00:00:00Z"));

        Map<String, List<String>> overSpan = params(
                "start_at", List.of("2026-08-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"));
        assertThatThrownBy(() -> TraceListQueryParser.parse(overSpan))
                .isInstanceOfSatisfying(LightAiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
    }

    @Test
    void exactTraceIdIgnoresRangeAndOtherFilters() {
        Map<String, List<String>> params = params(
                "trace_id", List.of("trace-abc-123"),
                "status", List.of("FAILED"));
        TraceListQueryParser.TraceListQuery query = TraceListQueryParser.parse(params);
        assertThat(query.exactId()).isTrue();
        assertThat(query.exactTraceId()).isEqualTo("trace-abc-123");
        // 精确分支忽略业务筛选与分页：业务筛选被清空，时间范围为空
        assertThat(query.startAt()).isNull();
        assertThat(query.statuses()).isEmpty();
        assertThat(query.page().page()).isEqualTo(1);
    }

    @Test
    void multiValueFiltersSplitCommasAndCapAt20() {
        List<String> twenty = List.of("app-a", "app-b", "app-c", "app-d", "app-e", "app-f",
                "app-g", "app-h", "app-i", "app-j", "app-k", "app-l", "app-m", "app-n",
                "app-o", "app-p", "app-q", "app-r", "app-s", "app-t");
        Map<String, List<String>> params = params(
                "start_at", List.of("2026-09-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"),
                "application", List.of("app-x,app-y,app-z"),
                "status", List.of("SUCCEEDED,FAILED"));
        TraceListQueryParser.TraceListQuery query = TraceListQueryParser.parse(params);
        assertThat(query.applications()).containsExactly("app-x", "app-y", "app-z");

        List<String> twentyOne = new java.util.ArrayList<>(twenty);
        twentyOne.add("app-u");
        Map<String, List<String>> overLimit = params(
                "start_at", List.of("2026-09-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"),
                "application", twentyOne);
        assertThatThrownBy(() -> TraceListQueryParser.parse(overLimit))
                .isInstanceOf(LightAiException.class);
    }

    @Test
    void rejectsUnknownEnumAndTriStateAndMsBounds() {
        Map<String, List<String>> badStatus = params(
                "start_at", List.of("2026-09-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"),
                "status", List.of("DONE"));
        assertThatThrownBy(() -> TraceListQueryParser.parse(badStatus))
                .isInstanceOf(LightAiException.class);

        Map<String, List<String>> badSourceMode = params(
                "start_at", List.of("2026-09-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"),
                "source_mode", List.of("SDK"));
        assertThatThrownBy(() -> TraceListQueryParser.parse(badSourceMode))
                .isInstanceOf(LightAiException.class);

        Map<String, List<String>> badTriState = params(
                "start_at", List.of("2026-09-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"),
                "has_retry", List.of("yes"));
        assertThatThrownBy(() -> TraceListQueryParser.parse(badTriState))
                .isInstanceOf(LightAiException.class);

        Map<String, List<String>> maxOverLimit = params(
                "start_at", List.of("2026-09-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"),
                "max_total_ms", List.of("600001"));
        assertThatThrownBy(() -> TraceListQueryParser.parse(maxOverLimit))
                .isInstanceOf(LightAiException.class);

        Map<String, List<String>> tagOnlyKey = params(
                "start_at", List.of("2026-09-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"),
                "tag_key", List.of("env"));
        assertThatThrownBy(() -> TraceListQueryParser.parse(tagOnlyKey))
                .isInstanceOf(LightAiException.class);
    }

    @Test
    void acceptsValidEnumsAndTriStates() {
        Map<String, List<String>> params = params(
                "start_at", List.of("2026-09-01T00:00:00Z"),
                "end_at", List.of("2026-09-02T00:00:00Z"),
                "status", List.of("SUCCEEDED,FAILED"),
                "source_mode", List.of("EMBEDDED"),
                "attempt_type", List.of("RETRY"),
                "usage_source", List.of("MIXED"),
                "requested_stream", List.of("true"),
                "has_retry", List.of("false"),
                "min_total_ms", List.of("100"),
                "max_total_ms", List.of("600000"));
        TraceListQueryParser.TraceListQuery query = TraceListQueryParser.parse(params);
        assertThat(query.statuses()).containsExactly("SUCCEEDED", "FAILED");
        assertThat(query.sourceModes()).containsExactly("EMBEDDED");
        assertThat(query.usageSources()).containsExactly("MIXED");
        assertThat(query.requestedStream()).isTrue();
        assertThat(query.hasRetry()).isFalse();
        assertThat(query.minTotalMs()).isEqualTo(100L);
        assertThat(query.maxTotalMs()).isEqualTo(600000L);
    }
}
