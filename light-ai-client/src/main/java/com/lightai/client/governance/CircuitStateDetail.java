package com.lightai.client.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 熔断状态详情（BACKEND_PLAN 4.3.3；429 不计失败口径已由引擎实现）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CircuitStateDetail(
        String id,
        String providerModelId,
        String credentialId,
        String credentialMasked,
        String state,
        long stateVersion,
        String policySnapshot,
        Instant windowStartedAt,
        int requestCount,
        int failureCount,
        int probeInflight,
        int probeSuccessCount,
        Instant openedAt,
        Instant nextProbeAt,
        String openSource,
        String lastReason,
        String lastErrorCode,
        boolean draftChanged,
        OffsetDateTime updatedAt,
        PendingCommand pendingCommand) {

    /** 未收敛的人工命令（HTTP 202 时返回，终态为 null）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PendingCommand(String id, String status, String errorCode) {
    }
}
