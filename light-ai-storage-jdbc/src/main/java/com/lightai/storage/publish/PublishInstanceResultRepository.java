package com.lightai.storage.publish;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * publish_instance_result 仓储端口（BE-041）。
 * U(publish_id, instance_id)；上报冲突由服务层按 reported_at 水位判定。
 */
public interface PublishInstanceResultRepository {

    void insertPending(Connection connection, UUID publishId, long fromSnapshotNo,
                       long targetSnapshotNo, List<UUID> instanceIds);

    void applyReport(Connection connection, UUID publishId, UUID instanceId, String status,
                     OffsetDateTime reportedAt, int retryCount, Long loadDurationMs,
                     String errorCode, String errorSummary);

    Optional<PublishInstanceResultRecord> find(Connection connection, UUID publishId, UUID instanceId);

    List<PublishInstanceResultRecord> listByPublish(Connection connection, UUID publishId);

    void markAllStatus(Connection connection, UUID publishId, String status);
}
