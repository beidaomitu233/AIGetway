package com.lightai.client.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 一次主动检测事实（DATABASE_PLAN provider_check_record；字段对齐 FE-009）。
 * provider_request_id 为受控字段，仅对有权限角色返回；
 * 检测属于运行数据，不修改配置 version 与 draft_changed。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderCheckRecord(
        String id,
        String targetType,
        String targetId,
        String mode,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        Integer totalMs,
        String traceId,
        String attemptId,
        UsageSummary usage,
        String errorCode,
        String errorSummary,
        String providerRequestId) {

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
}
