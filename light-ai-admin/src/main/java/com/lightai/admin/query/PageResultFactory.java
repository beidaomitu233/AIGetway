package com.lightai.admin.query;

import com.lightai.client.paging.PageResult;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * PageResult 组装（BE-004）：query_started_at 为本次查询共同起点，
 * data_updated_at 来自仓储水印（缺省等于查询起点）。
 * 排序表达已由 ListQuerySupport 白名单校验，原样进入响应。
 * Clock 注入保证可测试与可控时钟。
 */
public final class PageResultFactory {

    private final Clock clock;

    public PageResultFactory(Clock clock) {
        this.clock = clock;
    }

    public <T> PageResult<T> create(List<T> items, long total, ListQuerySupport.ListQuery query,
                                    OffsetDateTime dataUpdatedAt) {
        return create(items, total, query.page(), query.pageSize(), query.sort(), dataUpdatedAt);
    }

    public <T> PageResult<T> create(List<T> items, long total, int page, int pageSize,
                                    String sort, OffsetDateTime dataUpdatedAt) {
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        OffsetDateTime updated = dataUpdatedAt == null ? startedAt : dataUpdatedAt;
        return PageResult.of(
                items == null ? List.of() : List.copyOf(items),
                total,
                page,
                pageSize,
                sort == null ? "" : sort,
                startedAt,
                updated);
    }
}
