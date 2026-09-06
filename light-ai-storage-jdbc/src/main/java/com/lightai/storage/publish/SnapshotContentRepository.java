package com.lightai.storage.publish;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * ConfigSnapshot.content 装配与恢复端口（BE-038/040/041）。
 * 装配为固定键序白名单配置树；恢复按 id upsert 并重新生成 version。
 */
public interface SnapshotContentRepository {

    Map<String, Object> assemble(Connection connection, String timezone);

    String canonicalJson(Map<String, Object> content);

    Map<String, Long> summarize(Map<String, Object> content);

    List<RestoredEntity> restore(Connection connection, Map<String, Object> content);

    int deleteDraftObject(Connection connection, String entityType, String entityId);

    int restoreUndelete(Connection connection, String entityType, String entityId);

    /** 实体类型 → 快照内容键（provider → providers）。 */
    String contentKeyOf(String entityType);

    /** 恢复结果：类型与外部 ID。 */
    record RestoredEntity(String entityType, String entityId) {
    }
}
