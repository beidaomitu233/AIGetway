package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 校验结果（POST /admin/config/validate）。
 * change_summary 为发布摘要展示串（前端 config.ts 为字符串，
 * 由 config_validation.change_summary 计数 JSON 渲染，登记 H-019）。
 * target_snapshot_no 为校验时预分配的下一快照号；发布仍重新校验条件。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConfigValidationResultView(
        String validationId,
        String status,
        long baseSnapshotNo,
        long targetSnapshotNo,
        long draftRevision,
        String contentChecksum,
        OffsetDateTime validatedAt,
        OffsetDateTime expiresAt,
        String changeSummary,
        List<String> affectedAliasIds,
        List<ConfigValidationIssueView> issues) {

    public ConfigValidationResultView {
        affectedAliasIds = affectedAliasIds == null ? List.of() : List.copyOf(affectedAliasIds);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
