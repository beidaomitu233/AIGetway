package com.lightai.admin.draft;

import java.util.Set;

/**
 * 一次配置写命令（BE-006）。
 * action 为配置写动词集合（CREATE/UPDATE/ENABLE/DISABLE/DELETE），
 * 与 draft_change.change_type 及 audit_log.action 同词表。
 */
public record DraftWriteCommand(
        String requestId,
        String operatorId,
        String sourceMode,
        String sourceIpMasked,
        String action,
        String entityType,
        String entityId,
        long expectedVersion,
        EntityVersionReader versionReader,
        DraftEntityChange.Writer writer) {

    public static final Set<String> CONFIG_WRITE_ACTIONS =
            Set.of("CREATE", "UPDATE", "ENABLE", "DISABLE", "DELETE");

    public DraftWriteCommand {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("request_id 必填");
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operator_id 必填");
        }
        if (action == null || !CONFIG_WRITE_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("action 必须是配置写动词: " + CONFIG_WRITE_ACTIONS);
        }
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entity_type 必填");
        }
        if (writer == null) {
            throw new IllegalArgumentException("writer 必填");
        }
    }
}
