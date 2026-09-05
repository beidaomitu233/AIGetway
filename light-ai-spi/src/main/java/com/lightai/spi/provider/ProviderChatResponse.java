package com.lightai.spi.provider;

import java.util.List;

/**
 * Provider 非流式响应（4.7.2.3）：Adapter 只做协议转换，
 * Usage 缺失时由 Runtime 调用 estimateTokens 生成 ESTIMATED 结算。
 */
public record ProviderChatResponse(
        String content,
        String finishReason,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        String usageSource,
        String providerRequestId) {

    public static final String FINISH_STOP = "stop";
    public static final String FINISH_LENGTH = "length";
    public static final String FINISH_CONTENT_FILTER = "content_filter";

    public boolean succeeded() {
        return content != null && finishReason != null;
    }

    /** 统一 Usage 视图；缺项由 Runtime 估算后重建。 */
    public List<Long> tokens() {
        return List.of(inputTokens == null ? 0L : inputTokens,
                outputTokens == null ? 0L : outputTokens,
                totalTokens == null ? 0L : totalTokens);
    }
}
