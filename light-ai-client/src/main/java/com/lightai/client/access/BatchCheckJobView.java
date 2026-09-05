package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** 批量检测任务视图（GET /admin/batch-check-jobs/{id}）：任务与全部明细。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BatchCheckJobView(
        String id,
        String status,
        int totalCount,
        int completedCount,
        int successCount,
        int failureCount,
        int cancelledCount,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        List<ItemView> items) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ItemView(
            String id,
            String providerModelId,
            int sequence,
            String status,
            String errorCode) {
    }
}
