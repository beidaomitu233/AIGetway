package com.lightai.admin.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BE-034 概览查询校验测试：起止相等禁止、跨度上限、trends 必填 granularity、UUID 校验。
 */
class OverviewQueryParserTest {

    private Map<String, List<String>> base() {
        Map<String, List<String>> params = new HashMap<>();
        params.put("start_at", List.of("2026-09-01T00:00:00Z"));
        params.put("end_at", List.of("2026-09-02T00:00:00Z"));
        return params;
    }

    @Test
    void summaryIgnoresGranularityAndRequiresRange() {
        OverviewQueryParser.OverviewQuery query =
                OverviewQueryParser.parse(base(), false);
        assertThat(query.granularity()).isNull();
        assertThat(query.startAt()).isNotNull();
    }

    @Test
    void equalStartEndIsRejected() {
        Map<String, List<String>> params = base();
        params.put("end_at", List.of("2026-09-01T00:00:00Z"));
        assertThatThrownBy(() -> OverviewQueryParser.parse(params, false))
                .isInstanceOfSatisfying(LightAiException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
    }

    @Test
    void spanOver365DaysIsRejected() {
        Map<String, List<String>> params = base();
        params.put("start_at", List.of("2024-08-01T00:00:00Z"));
        assertThatThrownBy(() -> OverviewQueryParser.parse(params, false))
                .isInstanceOf(LightAiException.class);
    }

    @Test
    void trendsRequireGranularityAndValidateSpan() {
        Map<String, List<String>> missing = base();
        assertThatThrownBy(() -> OverviewQueryParser.parse(missing, true))
                .isInstanceOf(LightAiException.class);

        Map<String, List<String>> hourOverSpan = base();
        hourOverSpan.put("start_at", List.of("2026-07-01T00:00:00Z"));
        hourOverSpan.put("granularity", List.of("HOUR"));
        assertThatThrownBy(() -> OverviewQueryParser.parse(hourOverSpan, true))
                .isInstanceOf(LightAiException.class);

        Map<String, List<String>> valid = base();
        valid.put("granularity", List.of("DAY"));
        assertThat(OverviewQueryParser.parse(valid, true).granularity()).isEqualTo("DAY");
    }

    @Test
    void aliasIdMustBeUuid() {
        Map<String, List<String>> params = base();
        params.put("alias_id", List.of("not-a-uuid"));
        assertThatThrownBy(() -> OverviewQueryParser.parse(params, false))
                .isInstanceOf(LightAiException.class);
    }
}
