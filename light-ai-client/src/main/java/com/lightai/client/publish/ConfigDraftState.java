package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 全局草稿状态（BACKEND_PLAN 4.5.6.1，GET /admin/config/draft-state）。
 * first/last_modified_at 由 draft_change 活行时间范围派生；无差异时为 null。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConfigDraftState(
        long baseSnapshotNo,
        long draftRevision,
        int changeCount,
        String status,
        OffsetDateTime firstModifiedAt,
        OffsetDateTime lastModifiedAt) {
}
