package com.lightai.storage.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * config_validation 行（DATABASE_PLAN §30）。
 * change_summary/target_instances 以规范化 JSON 字符串承载（jsonb），
 * affected_alias_ids 为 ID 数组。
 */
public record ConfigValidationRecord(
        UUID validationId,
        long baseSnapshotNo,
        long targetSnapshotNo,
        long draftRevision,
        String contentChecksum,
        String status,
        int errorCount,
        int warningCount,
        OffsetDateTime validatedAt,
        OffsetDateTime expiresAt,
        String validatedBy,
        UUID usedByPublishId,
        String changeSummaryJson,
        List<String> affectedAliasIds,
        String targetInstancesJson) {

    public ConfigValidationRecord {
        if (validationId == null) {
            throw new IllegalArgumentException("validation_id 必填");
        }
        if (contentChecksum == null || contentChecksum.isBlank()) {
            throw new IllegalArgumentException("content_checksum 必填");
        }
        if (validatedBy == null || validatedBy.isBlank()) {
            throw new IllegalArgumentException("validated_by 必填");
        }
        affectedAliasIds = affectedAliasIds == null ? List.of() : List.copyOf(affectedAliasIds);
    }

    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";
}
