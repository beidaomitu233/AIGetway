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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private final java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        private boolean stream = false;
        private BigDecimal temperature;
        private BigDecimal topP;
        private Integer maxTokens;
        private List<String> stop;
        private String traceId;
        private RequestMetadata metadata;
        private Map<String, Object> providerOptions;
        private StreamOptions streamOptions;

        public Builder model(String model) { this.model = model; return this; }
        public Builder stream(boolean stream) { this.stream = stream; return this; }
        public Builder temperature(BigDecimal temperature) { this.temperature = temperature; return this; }
        public Builder temperature(double temperature) { this.temperature = BigDecimal.valueOf(temperature); return this; }
        public Builder topP(BigDecimal topP) { this.topP = topP; return this; }
        public Builder topP(double topP) { this.topP = BigDecimal.valueOf(topP); return this; }
        public Builder maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder stop(List<String> stop) { this.stop = stop; return this; }
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder metadata(RequestMetadata metadata) { this.metadata = metadata; return this; }
        public Builder providerOptions(Map<String, Object> options) { this.providerOptions = options; return this; }
        public Builder streamOptions(StreamOptions streamOptions) { this.streamOptions = streamOptions; return this; }

        public Builder messages(List<ChatMessage> messages) {
            this.messages.clear();
            if (messages != null) this.messages.addAll(messages);
            return this;
        }

        public Builder addMessage(String role, String content) {
            this.messages.add(new ChatMessage(role, content));
            return this;
        }

        public Builder addSystemMessage(String content) {
            return addMessage("system", content);
        }

        public Builder addUserMessage(String content) {
            return addMessage("user", content);
        }

        public Builder addAssistantMessage(String content) {
            return addMessage("assistant", content);
        }

        public UnifiedChatRequest build() {
            return new UnifiedChatRequest(model, messages, stream, temperature, topP, maxTokens, stop, traceId, metadata, providerOptions, streamOptions);
        }
    }
}
