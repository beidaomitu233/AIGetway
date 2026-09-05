package com.lightai.storage.draft;

import com.lightai.client.changes.FieldChange;
import java.util.List;
import java.util.UUID;

/**
 * 对象级脱敏差异（DATABASE_PLAN draft_change）。
 * (entity_type, entity_id) 唯一；changed_fields 秘密仅 changed=true。
 */
public record DraftChangeRecord(
        UUID id,
        String entityType,
        UUID entityId,
        String entityName,
        String changeType,
        List<FieldChange> changedFields,
        String modifiedBy,
        long entityVersion,
        long draftRevision) {

    public DraftChangeRecord {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entity_type 必填");
        }
        if (entityId == null) {
            throw new IllegalArgumentException("entity_id 必填");
        }
        if (changeType == null || changeType.isBlank()) {
            throw new IllegalArgumentException("change_type 必填");
        }
        if (modifiedBy == null || modifiedBy.isBlank()) {
            throw new IllegalArgumentException("modified_by 必填");
        }
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }
}
