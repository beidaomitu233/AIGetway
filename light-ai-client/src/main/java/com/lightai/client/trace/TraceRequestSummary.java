package com.lightai.client.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * Trace 请求摘要（BACKEND_PLAN 4.4.2，BE-032；字段对齐 FE-027 请求摘要区）。
 * 数量型字段来自 trace.request_summary JSON 字典，不含消息正文与 stop 值。
 * client_ip 仅敏感诊断权限可见，sampled_messages 仅诊断授权且样本 AVAILABLE 时序列化
 * （服务端序列化阶段裁剪，C-012）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TraceRequestSummary(
        String sourceMode,
        String accessCredentialName,
        String requestUser,
        String clientIp,
        String userAgent,
        long configSnapshotNo,
        Long messageCount,
        Long systemMessageCount,
        Long userMessageCount,
        Long assistantMessageCount,
        Long inputCharCount,
        Boolean requestedStream,
        BigDecimal temperature,
        BigDecimal topP,
        Long maxTokens,
        Long stopCount,
        java.util.List<String> providerOptionKeys,
        String contentSampleStatus,
        java.util.List<TraceSampledMessage> sampledMessages,
        String sampledResponse) {

    /** 脱敏后的诊断样本条目；仅 role/content 两个键。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TraceSampledMessage(String role, String content) {
    }
}
