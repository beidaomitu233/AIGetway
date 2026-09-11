package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 检测 Usage 摘要（DATABASE_PLAN channel_check_record.usage）。
 * source：ACTUAL（真实调用）或 ESTIMATED（估算）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UsageSummary(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        String source) {

    public static UsageSummary actual(long inputTokens, long outputTokens) {
        return new UsageSummary(inputTokens, outputTokens, inputTokens + outputTokens, "ACTUAL");
    }
}
