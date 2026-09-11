package com.lightai.storage.check;

import com.lightai.client.channel.UsageSummary;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 检测记录行（对应 DATABASE_PLAN channel_check_record）。
 * status：SUCCEEDED/FAILED；channel_request_id 为受控字段。
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
        String channelRequestId,
        String errorCode,
        String errorSummary) {

    public static final String TARGET_CHANNEL = "CHANNEL";
    public static final String TARGET_UPSTREAM_MODEL = "UPSTREAM_MODEL";
    public static final String TARGET_CHANNEL_CREDENTIAL = "CHANNEL_CREDENTIAL";
    public static final String TARGET_ROUTE_CANDIDATE = "ROUTE_CANDIDATE";
}
