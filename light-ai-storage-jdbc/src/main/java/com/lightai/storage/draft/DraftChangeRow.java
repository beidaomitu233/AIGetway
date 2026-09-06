package com.lightai.storage.draft;

import com.lightai.client.changes.FieldChange;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * draft_change 查询行（BE-037）：与 DATABASE_PLAN §29 列一致，
 * 附带 created_at/updated_at 供列表展示与时间范围派生。
 */
public record DraftChangeRow(
        UUID id,
        String entityType,
        UUID entityId,
        String entityName,
        String changeType,
        List<FieldChange> changedFields,
        String modifiedBy,
        long entityVersion,
        long draftRevision,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public DraftChangeRow {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }
}
