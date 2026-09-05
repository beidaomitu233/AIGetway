package com.lightai.storage.access;

import java.sql.Connection;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * object_runtime_state 只读/检测回写端口（DATABASE_PLAN §11，R类）。
 * 运行状态即时更新，不进入配置草稿与快照；配置启停与运行健康相互独立。
 */
public interface ObjectRuntimeStateRepository {

    /** 实体最近运行健康摘要；无记录的实体不出现在结果中。 */
    Map<UUID, RuntimeStateRow> find(Connection connection, String entityType, Collection<UUID> entityIds);

    /**
     * 检测结束回写：upsert connection_status / health_status / 最近时间与错误。
     * state_version 以 CAS 递增；并发检测以最后写入为准（运行态，不参与乐观版本）。
     */
    void upsertAfterCheck(Connection connection, String entityType, UUID entityId,
                          String connectionStatus, String healthStatus, boolean success,
                          String errorCode, String errorSummary);

    record RuntimeStateRow(
            String connectionStatus,
            String healthStatus,
            java.time.OffsetDateTime resetAt,
            java.time.OffsetDateTime lastSuccessAt,
            java.time.OffsetDateTime lastCheckedAt,
            java.time.OffsetDateTime lastFailedAt,
            String lastErrorCode,
            String lastErrorSummary) {
    }
}
