package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** 审计详情：增加 source_mode 与脱敏来源摘要；无 Secret 摘要前值。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditLogDetail(
        String id,
        OffsetDateTime createdAt,
        String requestId,
        String operatorId,
        String action,
        String entityType,
        String entityId,
        String result,
        List<com.lightai.client.changes.FieldChange> changes,
        String errorCode,
        String errorSummary,
        String sourceMode,
        String sourceIpMasked) {
}
