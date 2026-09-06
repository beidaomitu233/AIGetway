package com.lightai.storage.cleanup;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 留存物理清理仓储（DATABASE_PLAN 留存策略 / BE-048）。
 */
public interface RetentionDeletionRepository {

    List<String> findExpiredTraceIds(Connection connection, OffsetDateTime cutoff, int limit);

    int deleteTraceDetails(Connection connection, List<String> traceIds);

    int deleteTraces(Connection connection, List<String> traceIds);

    long deleteExpiredAudit(Connection connection, OffsetDateTime cutoff);

    long deleteExpiredSamples(Connection connection, OffsetDateTime cutoff);

    long deleteExpiredUsage(Connection connection, OffsetDateTime cutoff);

    long pendingAggregationEvents(Connection connection);
}
