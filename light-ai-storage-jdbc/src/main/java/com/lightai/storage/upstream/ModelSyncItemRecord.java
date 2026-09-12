package com.lightai.storage.upstream;

import java.util.UUID;

/**
 * model_sync_item 表行（DATABASE_PLAN DB-213，V5 新增，只追加）。
 * change_type：NEW / CHANGED / RETIRED / CONFLICT。
 * before_json / after_json 为同步前后白名单快照；conflict_fields 为与人工锁定字段
 * （upstream_model.locked_fields）冲突的字段名列表。
 */
public record ModelSyncItemRecord(
        UUID id,
        UUID jobId,
        UUID upstreamModelId,
        String modelId,
        String changeType,
        String beforeJson,
        String afterJson,
        String conflictFields) {
}
