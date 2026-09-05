package com.lightai.storage.check;

import com.lightai.client.provider.UsageSummary;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 检测记录行（对应 DATABASE_PLAN provider_check_record）。
 * status：SUCCEEDED/FAILED；provider_request_id 为受控字段。
 */
public record CheckRecordRow(
        UUID id,
        String targetType,
        UUID targetId,
        String mode,
        String status,
        String operatorId,
        String traceId,
        String attemptId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        int totalMs,
        UsageSummary usage,
        String providerRequestId,
        String errorCode,
        String errorSummary) {

    public static final String TARGET_PROVIDER = "PROVIDER";
    public static final String TARGET_PROVIDER_MODEL = "PROVIDER_MODEL";
    public static final String TARGET_CREDENTIAL = "CREDENTIAL";
    public static final String TARGET_ROUTE_CANDIDATE = "ROUTE_CANDIDATE";
}
