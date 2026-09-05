package com.lightai.admin.storage;

import com.lightai.storage.draft.DraftStateSnapshot;
import com.lightai.storage.runtimeconfig.RuntimeConfigState;
import java.util.Optional;

/**
 * 管理端全局状态读取：bootstrap 输出草稿修订、待发布变更数与活动快照号。
 * 无存储装配（LOCAL_RUNTIME）时返回零值默认。
 */
public interface ManagementStateReader {

    ManagementState read();

    record ManagementState(long currentSnapshotNo, String timezone, long draftRevision, int draftChangeCount) {

        public static ManagementState defaults(String timezone) {
            return new ManagementState(0, timezone, 0, 0);
        }
    }

    /** 由 DraftStateSnapshot/RuntimeConfigState 组装。 */
    static ManagementState of(Optional<RuntimeConfigState> runtime, Optional<DraftStateSnapshot> draft,
                              String fallbackTimezone) {
        long snapshotNo = runtime.map(RuntimeConfigState::currentSnapshotNo).orElse(0L);
        String timezone = runtime.map(RuntimeConfigState::timezone).orElse(fallbackTimezone);
        long revision = draft.map(DraftStateSnapshot::draftRevision).orElse(0L);
        int changeCount = draft.map(DraftStateSnapshot::changeCount).orElse(0);
        return new ManagementState(snapshotNo, timezone, revision, changeCount);
    }
}
