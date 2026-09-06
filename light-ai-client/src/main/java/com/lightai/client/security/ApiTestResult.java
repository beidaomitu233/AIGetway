package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 在线测试结果：response 为 UnifiedChatResponse；流式测试返回 StreamEvent SSE。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiTestResult(
        com.lightai.client.chat.UnifiedChatResponse response,
        String traceId,
        long totalMs) {
}
