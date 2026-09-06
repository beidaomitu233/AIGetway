package com.lightai.storage.publish;

import java.util.List;
import java.util.UUID;

/**
 * config_validation_issue 行（DATABASE_PLAN §31）。
 * message/suggestion 为安全文案；related_entity_ids 为引用对象 ID。
 */
public record ConfigValidationIssueRecord(
        UUID validationId,
        String severity,
        String code,
        String entityType,
        UUID entityId,
        String entityName,
        String fieldPath,
        String message,
        String suggestion,
        List<String> relatedEntityIds) {

    public ConfigValidationIssueRecord {
        if (validationId == null) {
            throw new IllegalArgumentException("validation_id 必填");
        }
        if (severity == null || severity.isBlank()) {
            throw new IllegalArgumentException("severity 必填");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 必填");
        }
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entity_type 必填");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 必填");
        }
        if (suggestion == null || suggestion.isBlank()) {
            throw new IllegalArgumentException("suggestion 必填");
        }
        relatedEntityIds = relatedEntityIds == null ? List.of() : List.copyOf(relatedEntityIds);
    }
}
