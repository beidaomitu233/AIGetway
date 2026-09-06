package com.lightai.storage.publish;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * config_snapshot 行（DATABASE_PLAN §32）。
 * content 为规范化白名单配置树 JSON；状态可迁移，content/checksum 不可变。
 */
public record ConfigSnapshotRecord(
        long snapshotNo,
        int schemaVersion,
        String status,
        String contentJson,
        String contentChecksum,
        String contentSummaryJson,
        OffsetDateTime activatedAt,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_ABORTED = "ABORTED";
}
