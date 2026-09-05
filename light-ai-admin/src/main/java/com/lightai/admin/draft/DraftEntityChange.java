package com.lightai.admin.draft;

import com.lightai.client.changes.FieldChange;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * 配置写事务中的实体变更产物：实体写入由调用方回调完成，
 * 回调返回脱敏前差异与实体元数据，草稿差异与审计统一在此提交。
 */
public record DraftEntityChange(
        String entityType,
        UUID entityId,
        String entityName,
        String changeType,
        long entityVersion,
        List<FieldChange> changes) {

    public DraftEntityChange {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entity_type 必填");
        }
        if (entityId == null) {
            throw new IllegalArgumentException("entity_id 必填");
        }
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entity_name 必填");
        }
        if (changeType == null || changeType.isBlank()) {
            throw new IllegalArgumentException("change_type 必填");
        }
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** 实体写入回调：在草稿锁与版本校验之后执行，失败随事务回滚。 */
    public interface Writer {
        DraftEntityChange write(Connection connection);
    }
}
