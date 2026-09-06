package com.lightai.storage.publish;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * publish_record 仓储端口（BE-040/042）。
 * validation_id 唯一：同一校验重复发布由服务层返回既有记录。
 */
public interface PublishRecordRepository {

    void insert(Connection connection, PublishRecordRecord record);

    void updateOutcome(Connection connection, UUID id, String status,
                       OffsetDateTime completedAt, OffsetDateTime convergedAt,
                       Long durationMs, String errorCode, String errorSummary);

    Optional<PublishRecordRecord> find(Connection connection, UUID id);

    Optional<PublishRecordRecord> findByValidation(Connection connection, UUID validationId);

    List<PublishRecordRecord> list(Connection connection, PublishRecordFilter filter,
                                   String sortExpression, int limit, long offset);

    long count(Connection connection, PublishRecordFilter filter);

    List<PublishRecordRecord> listUnfinished(Connection connection);

    /** 发布历史筛选。 */
    record PublishRecordFilter(
            Set<String> statuses,
            String publishedBy,
            Long snapshotNo,
            OffsetDateTime startFrom,
            OffsetDateTime startTo,
            String keyword) {

        public PublishRecordFilter {
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        }
    }
}
