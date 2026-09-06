package com.lightai.storage.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * publish_record 行（DATABASE_PLAN §33）。
 * completed_at 保留首轮结束，converged_at 记录最终收敛；两字段语义分开。
 */
public record PublishRecordRecord(
        UUID id,
        UUID validationId,
        long fromSnapshotNo,
        long targetSnapshotNo,
        long draftRevision,
        String status,
        String publishedBy,
        String publishNote,
        List<String> acknowledgedWarningIds,
        List<UUID> targetInstanceIds,
        OffsetDateTime completedAt,
        OffsetDateTime convergedAt,
        Long durationMs,
        String errorCode,
        String errorSummary,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String STATUS_PREPARING = "PREPARING";
    public static final String STATUS_ACTIVATING = "ACTIVATING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String STATUS_FAILED = "FAILED";
}
