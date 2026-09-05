package com.lightai.storage.check;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * provider_check_record 表行（DATABASE_PLAN §12，I类不可变）。
 * 不含请求内容与密钥；usage 为独立检测指标，不计入业务 Usage/Cost。
 */
public record CheckRecord(
        UUID id,
        String targetType,
        UUID targetId,
        String mode,
        String status,
        String operatorId,
        String traceId,
        UUID attemptId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        int totalMs,
        Long usageInputTokens,
        Long usageOutputTokens,
        Long usageTotalTokens,
        String usageSource,
        String providerRequestId,
        String errorCode,
        String errorSummary) {

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
}
