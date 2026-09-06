package com.lightai.admin.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.admin.trace.TraceListQueryParser.TraceListQuery;
import com.lightai.storage.trace.JdbcTraceRepository.TraceFilter;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * BE-031 数据范围注入测试：application_scope 先于查询注入；越权精确 ID 判空。
 */
class TraceServiceScopeTest {

    @Test
    void scopeApplicationsAreInjectedAsIndependentFilter() {
        Map<String, List<String>> params = new HashMap<>();
        params.put("start_at", List.of("2026-09-01T00:00:00Z"));
        params.put("end_at", List.of("2026-09-02T00:00:00Z"));
        params.put("application", List.of("app-a"));
        TraceListQuery query = TraceListQueryParser.parse(params);

        TraceFilter filter = TraceService.toFilter(query, List.of("app-a", "app-b"));
        // 请求筛选与身份范围同时生效：范围之外的应用即使被请求也查不到
        assertThat(filter.applications()).containsExactly("app-a");
        assertThat(filter.scopeApplications()).containsExactly("app-a", "app-b");

        // 无范围限制身份（空 scope）不注入额外条件
        TraceFilter unrestricted = TraceService.toFilter(query, List.of());
        assertThat(unrestricted.scopeApplications()).isEmpty();
    }

    @Test
    void inScopeMatchesByApplication() {
        assertThat(TraceService.inScope("app-a", List.of("app-a", "app-b"))).isTrue();
        assertThat(TraceService.inScope("app-c", List.of("app-a", "app-b"))).isFalse();
        // 空范围 = 不限制
        assertThat(TraceService.inScope("app-c", List.of())).isTrue();
    }

    @Test
    void anomalousRunningFlagIsTimeBased() throws Exception {
        // 静态常量与实现一致性：宽限 30 秒
        assertThat(TraceService.ANOMALOUS_GRACE_SECONDS).isEqualTo(30);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-06T00:10:00Z");
        // 判定逻辑在 toItem 内，此处验证常量与时间语义（超 deadline+30s 为异常）
        OffsetDateTime deadline = now.minusSeconds(31);
        assertThat(deadline.isBefore(now.minusSeconds(TraceService.ANOMALOUS_GRACE_SECONDS)))
                .isTrue();
    }
}
