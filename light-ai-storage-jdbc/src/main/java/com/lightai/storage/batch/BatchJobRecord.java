package com.lightai.storage.batch;

import java.time.OffsetDateTime;
import java.util.UUID;

/** batch_check_job 表行（DATABASE_PLAN §13，运行任务不改草稿）。 */
public record BatchJobRecord(
        UUID id,
        String status,
        String operatorId,
        int totalCount,
        int completedCount,
        int successCount,
        int failureCount,
        int cancelledCount,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        String commandJson,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
