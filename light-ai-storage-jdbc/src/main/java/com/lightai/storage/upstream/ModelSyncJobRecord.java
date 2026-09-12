package com.lightai.storage.upstream;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * model_sync_job 表行（DATABASE_PLAN DB-213，V5 新增）。
 * 记录一次上游模型同步预览；idempotency_key 作用域为渠道，同键重放返回原作业。
 * status：PREVIEWED（预览生成）/ COMMITTED（已提交生效）/ DISCARDED（废弃）/
 * EXPIRED（超时未提交）/ FAILED（预览失败）。summary_json 为新增/变化/下线/冲突计数摘要。
 */
public record ModelSyncJobRecord(
        UUID id,
        UUID channelId,
        String status,
        String idempotencyKey,
        String requestedBy,
        OffsetDateTime previewExpiresAt,
        String summaryJson,
        OffsetDateTime committedAt,
        String errorCode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
