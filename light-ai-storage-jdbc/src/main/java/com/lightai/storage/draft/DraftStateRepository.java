package com.lightai.storage.draft;

import java.sql.Connection;
import java.util.Optional;

/**
 * 全局草稿锁仓储端口。锁语义由实现保证：lock 必须是 SELECT ... FOR UPDATE，
 * 在业务事务内串行化并发配置写。
 */
public interface DraftStateRepository {

    Optional<DraftStateSnapshot> find(Connection connection);

    /** 事务内行锁读取；必须在活动事务中调用。 */
    DraftStateSnapshot lock(Connection connection);

    /** 原子递增 draft_revision 并维护 change_count；返回新快照。 */
    DraftStateSnapshot bumpRevision(Connection connection, int changeCountDelta);
}
