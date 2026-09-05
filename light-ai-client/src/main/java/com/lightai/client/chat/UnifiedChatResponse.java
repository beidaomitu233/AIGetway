package com.lightai.client.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 非流式统一响应（BACKEND_PLAN 2.2）：id 固定为 trace_id，
 * created 为调用开始 Unix 秒，model 为 Alias。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnifiedChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage,
        ResponseTraceInfo lightAi) {

    public record Choice(int index, Message message, String finishReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String role, String content) {
    }
}
