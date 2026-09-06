package com.lightai.admin.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.LightAiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BE-035 UsageQuery 校验测试：HOUR/DAY 跨度、usage_source 取值、多值上限、排序白名单。
 */
class UsageQueryParserTest {

    private Map<String, List<String>> base() {
        Map<String, List<String>> params = new HashMap<>();
        params.put("start_at", List.of("2026-09-01T00:00:00Z"));
        params.put("end_at", List.of("2026-09-02T00:00:00Z"));
        return params;
    }

    @Test
    void defaultsGranularityDayAndGroupAlias() {
        UsageQueryParser.UsageQuery query = UsageQueryParser.parse(base());
        assertThat(query.granularity()).isEqualTo("DAY");
        assertThat(query.groupBy()).isEqualTo("ALIAS");
        assertThat(query.groupPage().sort()).isEqualTo("total_cost desc");
        assertThat(query.groupPage().page()).isEqualTo(1);
        assertThat(query.groupPage().pageSize()).isEqualTo(20);
    }

    @Test
    void hourSpanOver31DaysRejectedDayAllowed() {
        Map<String, List<String>> hourOver = base();
        hourOver.put("start_at", List.of("2026-07-01T00:00:00Z"));
        hourOver.put("granularity", List.of("HOUR"));
        assertThatThrownBy(() -> UsageQueryParser.parse(hourOver))
                .isInstanceOf(LightAiException.class);

        Map<String, List<String>> dayOk = base();
        dayOk.put("start_at", List.of("2026-07-01T00:00:00Z"));
        assertThat(UsageQueryParser.parse(dayOk).granularity()).isEqualTo("DAY");
    }

    @Test
    void usageSourceMixedRejectedInAggregateDimensions() {
        Map<String, List<String>> params = base();
        params.put("usage_source", List.of("MIXED"));
        assertThatThrownBy(() -> UsageQueryParser.parse(params))
                .isInstanceOf(LightAiException.class);
    }

    @Test
    void groupSortWhitelistEnforced() {
        Map<String, List<String>> bad = base();
        bad.put("group_sort", List.of("total_tokens; drop table"));
        assertThatThrownBy(() -> UsageQueryParser.parse(bad))
                .isInstanceOf(LightAiException.class);

        Map<String, List<String>> good = base();
        good.put("group_sort", List.of("DIMENSION_NAME asc"));
        assertThat(UsageQueryParser.parse(good).groupPage().sort())
                .isEqualTo("dimension_name asc");
    }

    @Test
    void currencyMustBeThreeLetterCode() {
        Map<String, List<String>> bad = base();
        bad.put("currency", List.of("USDT!"));
        assertThatThrownBy(() -> UsageQueryParser.parse(bad))
                .isInstanceOf(LightAiException.class);

        Map<String, List<String>> good = base();
        good.put("currency", List.of("USD"));
        assertThat(UsageQueryParser.parse(good).currency()).isEqualTo("USD");
    }

    @Test
    void costSortRequestedDetection() {
        Map<String, List<String>> params = base();
        params.put("group_sort", List.of("total_cost desc"));
        assertThat(UsageQueryParser.parse(params).costSortRequested()).isTrue();

        Map<String, List<String>> other = base();
        other.put("group_sort", List.of("request_count desc"));
        assertThat(UsageQueryParser.parse(other).costSortRequested()).isFalse();
    }
}
