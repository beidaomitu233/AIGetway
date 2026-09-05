package com.lightai.spi.provider;

/**
 * 流式块（4.7.2.4）：Provider 内容按原顺序转 CONTENT，Usage 转 USAGE，
 * 正常结束转唯一 FINISH；不携带原始响应体。
 */
public record ProviderStreamChunk(Type type, String content, Long inputTokens, Long outputTokens,
                                  Long totalTokens, String finishReason) {

    public enum Type {
        CONTENT,
        USAGE,
        FINISH
    }

    public static ProviderStreamChunk content(String content) {
        return new ProviderStreamChunk(Type.CONTENT, content, null, null, null, null);
    }

    public static ProviderStreamChunk usage(Long inputTokens, Long outputTokens, Long totalTokens) {
        return new ProviderStreamChunk(Type.USAGE, null, inputTokens, outputTokens, totalTokens, null);
    }

    public static ProviderStreamChunk finish(String finishReason) {
        return new ProviderStreamChunk(Type.FINISH, null, null, null, null, finishReason);
    }
}
