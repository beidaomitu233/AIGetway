package com.lightai.storage.publish;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * 草稿撤销依赖查询端口（BE-038）。
 * blockers = 其他「新建草稿」（CREATE）引用目标对象的关系（4.5.1.5，RV-025）。
 */
public interface DraftDependencyRepository {

    List<Blocker> findCreateBlockers(Connection connection, String entityType, UUID entityId);

    /** 撤销阻塞项。 */
    record Blocker(String entityType, String entityId, String entityName) {
    }
}
