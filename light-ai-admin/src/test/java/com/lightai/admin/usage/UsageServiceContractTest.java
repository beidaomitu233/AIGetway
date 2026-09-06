package com.lightai.admin.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.admin.usage.UsageQueryParser.UsageQuery;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BE-035 查询口径测试：fingerprint 对同一组筛选稳定且与顺序无关、
 * 分页字段不参与指纹、维度列映射、百分比与费率计算。
 */
class UsageServiceContractTest {

    private static UsageQuery query(Map<String, List<String>> overrides) {
        Map<String, List<String>> params = new HashMap<>();
        params.put("start_at", List.of("2026-09-01T00:00:00Z"));
        params.put("end_at", List.of("2026-09-02T00:00:00Z"));
        params.put("granularity", List.of("HOUR"));
        params.putAll(overrides);
        return UsageQueryParser.parse(params);
    }

    @Test
    void fingerprintIgnoresMultiValueOrderAndPagination() {
        UsageQuery a = query(Map.of(
                "application", List.of("app-1,app-2"),
                "alias_id", List.of("11111111-1111-1111-1111-111111111111")));
        UsageQuery b = query(Map.of(
                "application", List.of("app-2", "app-1"),
                "alias_id", List.of("11111111-1111-1111-1111-111111111111"),
                "group_page", List.of("3"),
                "group_page_size", List.of("50")));
        OffsetDateTime start = OffsetDateTime.parse("2026-09-01T00:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-09-02T00:00:00Z");
        ZoneId zone = ZoneId.of("UTC");
        String fpA = UsageService.fingerprint(a, start, end, zone);
        String fpB = UsageService.fingerprint(b, start, end, zone);
        assertThat(fpA).isEqualTo(fpB).hasSize(64);

        // 筛选不同 → 指纹不同
        UsageQuery different = query(Map.of("application", List.of("app-1")));
        assertThat(UsageService.fingerprint(different, start, end, zone)).isNotEqualTo(fpA);
    }

    @Test
    void dimensionColumnMappingCoversAllGroupDimensions() {
        assertThat(UsageService.dimensionColumn("APPLICATION")).isEqualTo("application");
        assertThat(UsageService.dimensionColumn("ALIAS")).isEqualTo("alias_id");
        assertThat(UsageService.dimensionColumn("PROVIDER_MODEL")).isEqualTo("provider_model_id");
        assertThat(UsageService.dimensionColumn("CREDENTIAL_POOL")).isEqualTo("credential_pool_id");
        assertThat(UsageService.dimensionColumn("TRACE_STATUS")).isEqualTo("trace_status");
        assertThat(UsageService.dimensionColumn("USAGE_SOURCE")).isEqualTo("usage_source");
    }

    @Test
    void rateAndShareMathUsePercentWithHalfUp() {
        assertThat(UsageService.rate(1, 4)).isEqualByComparingTo("25.0000");
        assertThat(UsageService.rate(1, 3)).isEqualByComparingTo("33.3333");
        assertThat(UsageService.rate(0, 0)).isNull();
        assertThat(UsageService.share(1, 4)).isEqualByComparingTo("25.0000");
        assertThat(UsageService.share(new java.math.BigDecimal("0.5"),
                new java.math.BigDecimal("2"))).isEqualByComparingTo("25.0000");
        assertThat(UsageService.share(new java.math.BigDecimal("1"),
                java.math.BigDecimal.ZERO)).isNull();
    }

    @Test
    void backoffScheduleFollowsSpecBeforeSteadyState() {
        assertThat(UsageAggregator.backoffSeconds(0)).isEqualTo(60);
        assertThat(UsageAggregator.backoffSeconds(1)).isEqualTo(120);
        assertThat(UsageAggregator.backoffSeconds(4)).isEqualTo(960);
        assertThat(UsageAggregator.backoffSeconds(5)).isEqualTo(1800);
        assertThat(UsageAggregator.backoffSeconds(20)).isEqualTo(1800);
    }
}
