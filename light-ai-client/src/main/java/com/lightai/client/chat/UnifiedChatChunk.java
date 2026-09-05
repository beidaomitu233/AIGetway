package com.lightai.client.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 流式统一块（BACKEND_PLAN 2.2）：role 块 sequence=0，JSON 成功块 sequence 连续递增；
 * [DONE] 为分隔结束标识，不是 JSON 事件、不递增序号。
 * 可选 Usage 块 choices 为空数组并带 usage 与 light_ai.cost。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnifiedChatChunk(
        String id,
        String object,
        long created,
        String model,
        List<ChunkChoice> choices,
        Usage usage,
        ChunkTraceInfo lightAi) {

    public record ChunkChoice(int index, Delta delta, String finishReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChunkTraceInfo(String traceId, long sequence, String provider, String providerModel, CostInfo cost) {
    }
}
