package com.lightai.client;

import com.lightai.client.chat.CostInfo;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.client.chat.Usage;

/**
 * SDK 公开响应对象（BE-049，4.6.2.2）：封装核心字段与便捷获取方法。
 */
public record ChatResponse(
        String id,
        String model,
        String provider,
        String providerModel,
        String content,
        String finishReason,
        Usage usage,
        CostInfo cost,
        UnifiedChatResponse raw) {

    public String message() {
        return content;
    }

    public static ChatResponse fromUnified(UnifiedChatResponse u) {
        if (u == null) return null;
        String content = null;
        String finishReason = null;
        if (u.choices() != null && !u.choices().isEmpty()) {
            UnifiedChatResponse.Choice c = u.choices().get(0);
            if (c.message() != null) {
                content = c.message().content();
            }
            finishReason = c.finishReason();
        }
        String provider = u.lightAi() != null ? u.lightAi().provider() : null;
        String providerModel = u.lightAi() != null ? u.lightAi().providerModel() : null;
        CostInfo cost = u.lightAi() != null ? u.lightAi().cost() : null;
        return new ChatResponse(u.id(), u.model(), provider, providerModel, content, finishReason, u.usage(), cost, u);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String model;
        private String provider;
        private String providerModel;
        private String content;
        private String finishReason;
        private Usage usage;
        private CostInfo cost;
        private UnifiedChatResponse raw;

        public Builder id(String id) { this.id = id; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder providerModel(String providerModel) { this.providerModel = providerModel; return this; }
        public Builder content(String content) { this.content = content; return this; }
        public Builder finishReason(String finishReason) { this.finishReason = finishReason; return this; }
        public Builder usage(Usage usage) { this.usage = usage; return this; }
        public Builder usage(long promptTokens, long completionTokens, long totalTokens) {
            this.usage = new Usage(promptTokens, completionTokens, totalTokens, Usage.SOURCE_ACTUAL);
            return this;
        }
        public Builder cost(CostInfo cost) { this.cost = cost; return this; }
        public Builder raw(UnifiedChatResponse raw) { this.raw = raw; return this; }

        public ChatResponse build() {
            return new ChatResponse(id, model, provider, providerModel, content, finishReason, usage, cost, raw);
        }
    }
}