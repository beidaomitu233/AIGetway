package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 草稿差异摘要（GET /admin/config/draft-changes/summary）。
 * 计数与 change_type 对应；by_entity_type 按对象类别聚合。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DraftChangeSummary(
        long totalCount,
        long createCount,
        long updateCount,
        long enableCount,
        long disableCount,
        long deleteCount,
        Map<String, Long> byEntityType) {
}
