package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 检测记录视图（协议字典 ProviderCheckRecord）。
 * provider_request_id 为受控字段，仅对有权角色输出（C-012），序列化前裁剪。
 * usage 为检测消耗（独立运行指标，不计入业务 Usage/Cost）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProviderCheckRecordView(
        String id,
        String targetType,
        String targetId,
        String mode,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        int totalMs,
        String traceId,
        String attemptId,
        UsageView usage,
        String providerRequestId,
        String errorCode,
        String errorSummary) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsageView(Long inputTokens, Long outputTokens, Long totalTokens, String source) {
    }
}
