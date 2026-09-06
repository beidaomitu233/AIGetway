package com.lightai.storage.audit;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * audit_log 查询端口（BE-045）：4.5.5.1 筛选 + 稳定排序 + 分页；
 * 普通组合查询跨度 ≤31 天，request_id/id 精确查询可跨全留存期（服务层控制）。
 */
public interface AuditQueryRepository {

    List<AuditQueryRow> list(Connection connection, String filterSql, List<Object> filterValues,
                             String orderSql, long offset, int limit);

    long count(Connection connection, String filterSql, List<Object> filterValues);

    Optional<AuditQueryRow> find(Connection connection, UUID id);

    long countAll(Connection connection);

    record AuditQueryRow(
            UUID id,
            OffsetDateTime createdAt,
            String requestId,
            String operatorId,
            String action,
            String entityType,
            String entityId,
            String result,
            String changesJson,
            String errorCode,
            String errorSummary,
            String sourceMode,
            String sourceIpMasked) {
    }
}
