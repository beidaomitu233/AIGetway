package com.lightai.client.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 统一文本 Chat 请求（BACKEND_PLAN 2.2）。结构校验见 ChatRequestValidator；
 * 能力与上下文校验由运行内核按候选模型执行。未知字段在 HTTP 层拒绝。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnifiedChatRequest(
        String model,
        List<ChatMessage> messages,
        boolean stream,
        BigDecimal temperature,
        BigDecimal topP,
        Integer maxTokens,
        List<String> stop,
        String traceId,
        RequestMetadata metadata,
        Map<String, Object> providerOptions,
        StreamOptions streamOptions) {
}
