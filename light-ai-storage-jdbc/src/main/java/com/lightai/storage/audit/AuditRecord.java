package com.lightai.storage.audit;

import com.lightai.client.changes.FieldChange;
import java.util.List;
import java.util.UUID;

/**
 * 审计写入记录（DATABASE_PLAN audit_log）。
 * changes 必须已完成脱敏：敏感字段只有 field_path 与 changed=true。
 * result：SUCCEEDED/FAILED；人工命令受理用独立 action，不声称状态已变。
 */
public record AuditRecord(
        UUID id,
        String requestId,
        String operatorId,
        String action,
        String entityType,
        String entityId,
        String result,
        List<FieldChange> changes,
        String errorCode,
        String errorSummary,
        String sourceMode,
        String sourceIpMasked) {

    public static final String RESULT_SUCCEEDED = "SUCCEEDED";
    public static final String RESULT_FAILED = "FAILED";

    public AuditRecord {
        if (id == null) {
            throw new IllegalArgumentException("审计 id 必填");
        }
        requireText(requestId, "request_id");
        requireText(operatorId, "operator_id");
        requireText(action, "action");
        requireText(entityType, "entity_type");
        requireText(result, "result");
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public static AuditRecord succeeded(UUID id, String requestId, String operatorId, String action,
                                        String entityType, String entityId, List<FieldChange> changes,
                                        String sourceMode, String sourceIpMasked) {
        return new AuditRecord(id, requestId, operatorId, action, entityType, entityId,
                RESULT_SUCCEEDED, changes, null, null, sourceMode, sourceIpMasked);
    }

    public static AuditRecord failed(UUID id, String requestId, String operatorId, String action,
                                     String entityType, String entityId, String errorCode,
                                     String errorSummary, String sourceMode, String sourceIpMasked) {
        return new AuditRecord(id, requestId, operatorId, action, entityType, entityId,
                RESULT_FAILED, List.of(), errorCode, errorSummary, sourceMode, sourceIpMasked);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("审计 " + field + " 必填");
        }
    }
}
