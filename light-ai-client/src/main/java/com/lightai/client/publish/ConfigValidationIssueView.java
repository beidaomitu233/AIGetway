package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 校验问题（发布校验矩阵 4.5.2.3）。
 * ERROR 阻断发布；WARNING 需在发布命令中逐条确认。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConfigValidationIssueView(
        String code,
        String severity,
        String entityType,
        String entityId,
        String entityName,
        String fieldPath,
        String message,
        String suggestion,
        List<String> relatedEntityIds) {

    public static final String SEVERITY_ERROR = "ERROR";
    public static final String SEVERITY_WARNING = "WARNING";

    public ConfigValidationIssueView {
        relatedEntityIds = relatedEntityIds == null ? List.of() : List.copyOf(relatedEntityIds);
    }
}
