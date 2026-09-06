package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** 审计列表项（BE-045）；changes 已脱敏（敏感字段只有 field_path 与 changed=true）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditLogListItem(
        String id,
        OffsetDateTime createdAt,
        String requestId,
        String operatorId,
        String action,
        String entityType,
        String entityId,
        String result,
        String errorCode,
        List<com.lightai.client.changes.FieldChange> changes) {
}
