package com.lightai.client;

import com.lightai.client.chat.ChatMessage;
import com.lightai.client.chat.RequestMetadata;
import com.lightai.client.chat.StreamOptions;
import com.lightai.client.chat.UnifiedChatRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK 公开请求对象（BE-049，4.6.2.2）：不可变值对象，提供构建器与与协议对象双向转换。
 */
public record ChatRequest(
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

    public ChatRequest {
        messages = messages != null ? List.copyOf(messages) : List.of();
        stop = stop != null ? List.copyOf(stop) : null;
        providerOptions = providerOptions != null ? Collections.unmodifiableMap(new LinkedHashMap<>(providerOptions)) : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UnifiedChatRequest toUnified() {
        return new UnifiedChatRequest(model, messages, stream, temperature, topP, maxTokens, stop, traceId, metadata, providerOptions, streamOptions);
    }

    public static ChatRequest fromUnified(UnifiedChatRequest u) {
        if (u == null) return null;
        return new ChatRequest(u.model(), u.messages(), u.stream(), u.temperature(), u.topP(), u.maxTokens(), u.stop(), u.traceId(), u.metadata(), u.providerOptions(), u.streamOptions());
    }

    public static class Builder {
        private String model;
        private final List<ChatMessage> messages = new ArrayList<>();
        private boolean stream = false;
        private BigDecimal temperature;
        private BigDecimal topP;
        private Integer maxTokens;
        private List<String> stop;
        private String traceId;
        private RequestMetadata metadata;
        private Map<String, Object> providerOptions;
        private StreamOptions streamOptions;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder temperature(BigDecimal temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = BigDecimal.valueOf(temperature);
            return this;
        }

        public Builder topP(BigDecimal topP) {
            this.topP = topP;
            return this;
        }

        public Builder topP(double topP) {
            this.topP = BigDecimal.valueOf(topP);
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder stop(List<String> stop) {
            this.stop = stop;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder metadata(RequestMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder providerOptions(Map<String, Object> providerOptions) {
            this.providerOptions = providerOptions;
            return this;
        }

        public Builder streamOptions(StreamOptions streamOptions) {
            this.streamOptions = streamOptions;
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages.clear();
            if (messages != null) {
                this.messages.addAll(messages);
            }
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

        public ChatRequest build() {
            return new ChatRequest(model, messages, stream, temperature, topP, maxTokens, stop, traceId, metadata, providerOptions, streamOptions);
        }
    }
}