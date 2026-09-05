package com.lightai.client.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 业务响应 Token 用量（OpenAI 风格字段名）；来源标记 ACTUAL 或 ESTIMATED。
 * 存储侧 input/output 命名以 DATABASE_PLAN 为准，两者在结算层映射。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Usage(long promptTokens, long completionTokens, long totalTokens, String source) {

    public static final String SOURCE_ACTUAL = "ACTUAL";
    public static final String SOURCE_ESTIMATED = "ESTIMATED";

    public static Usage of(long promptTokens, long completionTokens, String source) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens, source);
    }
}
