package com.lightai.storage.draft;

import java.sql.Connection;
import java.util.UUID;

/**
 * config_draft_state 发布流转端口（BE-040）：PUBLISHING 锁定、失败释放、
 * 激活后基线切换。必须在业务事务内调用（与快照激活同事务）。
 */
public interface DraftPublishStateRepository {

    /** 取得全局发布锁：status=PUBLISHING 并记录 publish_record_id。 */
    void markPublishing(Connection connection, UUID publishRecordId);

    /** 发布失败/中止释放：恢复 EDITABLE 并清空持锁引用；草稿内容保持不变。 */
    void releaseToEditable(Connection connection);

    /** 原子激活后基线切换：base_snapshot_no=目标、change_count=0、恢复 EDITABLE。 */
    void activateBaseline(Connection connection, long targetSnapshotNo);
}
