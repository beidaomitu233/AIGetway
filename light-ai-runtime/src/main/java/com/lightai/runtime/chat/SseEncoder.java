package com.lightai.runtime.chat;

import com.lightai.client.chat.UnifiedChatChunk;
import com.lightai.client.error.UnifiedError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.lightai.client.json.ProtocolJson;

/**
 * SSE 编码（BE-028）：role 块 sequence=0 连续递增；最后 [DONE] 为分隔结束标识，
 * 不是 JSON 事件、不递增序号；错误以 UnifiedErrorEnvelope 单事件发送并关闭，无 finish 与 DONE。
 */
public final class SseEncoder {

    private SseEncoder() {
    }

    public static String chunk(UnifiedChatChunk chunk) {
        return "data: " + json(chunk) + "\n\n";
    }

    public static String done() {
        return "data: [DONE]\n\n";
    }

    public static String error(UnifiedError error) {
        return "data: " + json(java.util.Map.of("error", error)) + "\n\n";
    }

    public static String comment(String text) {
        return ": " + text + "\n\n";
    }

    private static String json(Object value) {
        try {
            return ProtocolJson.protocol().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSE 编码失败", e);
        }
    }
}
