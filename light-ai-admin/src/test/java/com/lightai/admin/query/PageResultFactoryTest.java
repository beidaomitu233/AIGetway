package com.lightai.admin.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * PageResult 组装验收（BE-004）：空集结构、共同查询起点、水印缺省、
 * 排序表达回传；解析口径由 ListQuerySupportTest 覆盖。
 */
class PageResultFactoryTest {

    private final PageResultFactory factory = new PageResultFactory(Clock.fixed(
            Instant.parse("2026-09-05T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void emptyResultProducesContractShape() {
        var query = ListQuerySupport.parse("2", "10", "name desc",
                Set.of("created_at", "name", "updated_at"), "created_at desc");
        var page = factory.create(List.of(), 0, query, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.sort()).isEqualTo("name desc");
        assertThat(page.queryStartedAt().toString()).isEqualTo("2026-09-05T08:00Z");
        assertThat(page.dataUpdatedAt()).isEqualTo(page.queryStartedAt());
    }

    @Test
    void dataUpdatedAtDefaultsToQueryStartWhenWatermarkAbsent() {
        var page = factory.create(List.of("a"), 1, 1, 20, "updated_at desc", null);
        assertThat(page.items()).containsExactly("a");
        assertThat(page.dataUpdatedAt()).isEqualTo(page.queryStartedAt());
    }

    @Test
    void providedWatermarkIsKeptSeparateFromQueryStart() {
        var watermark = java.time.OffsetDateTime.parse("2026-09-05T07:59:00Z");
        var page = factory.create(List.of(), 3, 1, 20, "created_at desc", watermark);
        assertThat(page.dataUpdatedAt()).isEqualTo(watermark);
        assertThat(page.queryStartedAt()).isAfter(watermark.toInstant().atOffset(ZoneOffset.UTC));
    }

    @Test
    void nullItemsCollapseToEmptyList() {
        var page = factory.create(null, 0, 1, 20, "", null);
        assertThat(page.items()).isEmpty();
        assertThat(page.sort()).isEmpty();
    }
}
