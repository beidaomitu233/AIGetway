package com.lightai.storage.draft;

import java.util.UUID;

/**
 * config_draft_state 单例快照。
 * draft_revision 保护跨对象校验/撤销全部/发布；对象自身 version 保护并发编辑。
 */
public record DraftStateSnapshot(
        long baseSnapshotNo,
        long draftRevision,
        DraftStatus status,
        UUID publishRecordId,
        int changeCount) {
}
