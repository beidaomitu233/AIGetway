package com.lightai.storage.check;

import java.time.OffsetDateTime;
import java.util.UUID;

/** batch_check_item 表行（DATABASE_PLAN §14，R类）；sequence 从 1 递增。 */
public record BatchCheckItemRecord(
        UUID id,
        UUID jobId,
        UUID providerModelId,
        int sequence,
        String status,
        UUID checkRecordId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        String errorCode) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
