package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 草稿差异行（GET /admin/config/draft-changes，前端 config.ts DraftChange 契约）。
 * revertable/revert_blockers 实时查引用（DATABASE_PLAN §3 派生规则）；
 * modified_by_name 为操作者展示名，V1.0 暂取 modified_by（C-025 同口径）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DraftChangeItem(
        String id,
        String entityType,
        String entityId,
        String entityName,
        String changeType,
        List<FieldChangeView> changedFields,
        List<DraftDependencyRef> dependencySummary,
        boolean revertable,
        List<String> revertBlockers,
        String modifiedBy,
        String modifiedByName,
        OffsetDateTime modifiedAt,
        long entityVersion) {

    public DraftChangeItem {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
        dependencySummary = dependencySummary == null ? List.of() : List.copyOf(dependencySummary);
        revertBlockers = revertBlockers == null ? List.of() : List.copyOf(revertBlockers);
    }
}
