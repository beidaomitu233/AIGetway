package com.lightai.storage.draft;

import java.sql.Connection;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 草稿差异仓储端口。upsert 需在业务事务内调用；
 * 返回 true 表示新增差异（change_count 同事务 +1），false 表示覆盖既有差异。
 */
public interface DraftChangeRepository {

    boolean upsert(Connection connection, DraftChangeRecord record);

    /** 单实体是否存在未发布差异（详情 draft_changed）。 */
    boolean existsByEntity(Connection connection, String entityType, UUID entityId);

    /** 批量查询存在差异的实体集合（列表 draft_changed，避免 N+1）。 */
    Set<UUID> findExistingEntityIds(Connection connection, String entityType, Collection<UUID> entityIds);
}
