package com.lightai.storage.draft;

import java.sql.Connection;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 草稿差异仓储端口。upsert 需在业务事务内调用；
 * 返回 true 表示新增差异（change_count 同事务 +1），false 表示覆盖既有差异。
 */
public interface DraftChangeRepository {

    boolean upsert(Connection connection, DraftChangeRecord record);

    /** 批量查询存在差异的对象 id（列表 draft_changed 标记，避免 N+1）。 */
    Set<UUID> findChangedEntityIds(Connection connection, String entityType,
                                   Collection<UUID> entityIds);

    /** 最近差异操作者（详情页 updated_by 展示来源）。 */
    Optional<String> findLatestModifier(Connection connection, String entityType, UUID entityId);
}
