package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 撤销依赖提示引用（DraftChange.dependency_summary）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DraftDependencyRef(
        String entityType,
        String entityId,
        String entityName) {
}
