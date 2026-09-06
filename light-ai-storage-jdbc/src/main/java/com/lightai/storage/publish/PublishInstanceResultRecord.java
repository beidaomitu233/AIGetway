package com.lightai.storage.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * publish_instance_result 行（DATABASE_PLAN §34）。
 * 能力字段来自 runtime_instance 最近心跳（列表联查填充）。
 */
public record PublishInstanceResultRecord(
        UUID id,
        UUID publishId,
        UUID instanceId,
        long fromSnapshotNo,
        long targetSnapshotNo,
        String status,
        int retryCount,
        Long loadDurationMs,
        OffsetDateTime reportedAt,
        String errorCode,
        String errorSummary,
        OffsetDateTime updatedAt,
        String runtimeMode,
        String runtimeVersion,
        List<String> supportedSchemaVersions,
        List<String> loadedAdapterTypes) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PREPARING = "PREPARING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_ACTIVATING = "ACTIVATING";
    public static final String STATUS_LOADED = "LOADED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";
}
