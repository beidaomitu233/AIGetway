package com.lightai.client.paging;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 管理列表统一分页结构。query_started_at 为共同查询起点，
 * data_updated_at 标记数据最后更新，用于跨查询一致性核对。
 * sort 为生效排序表达（如 updated_at desc），未经白名单校验的排序不得进入。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PageResult<T>(
        List<T> items,
        long total,
        int page,
        int pageSize,
        String sort,
        OffsetDateTime queryStartedAt,
        OffsetDateTime dataUpdatedAt) {

    public static <T> PageResult<T> of(List<T> items, long total, int page, int pageSize,
                                       String sort, OffsetDateTime queryStartedAt, OffsetDateTime dataUpdatedAt) {
        return new PageResult<>(items, total, page, pageSize, sort, queryStartedAt, dataUpdatedAt);
    }
}
