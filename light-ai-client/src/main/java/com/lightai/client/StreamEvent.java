package com.lightai.client;

import com.lightai.client.chat.CostInfo;
import com.lightai.client.chat.Usage;

/**
 * 流式统一事件（BE-049/052，2.6.10、4.6.2.2）：
 * START: 连接建立与路径确定；
 * DELTA: 文本增量块；
 * USAGE: 最终 Token 统计与可选费用；
 * DONE: 结束标志与最终 finish_reason。
 */
public record StreamEvent(
        StreamEventType event,
        String traceId,
        long sequence,
        String model,
        String provider,
        String providerModel,
        String delta,
        String finishReason,
        Usage usage,
        CostInfo cost,
        Long totalMs) {

    public StreamEventType type() {
        return event;
    }

    public String deltaContent() {
        return delta;
    }

    public static StreamEvent start(String traceId) {
        return start(traceId, null, null, null);
    }

    public static StreamEvent start(String traceId, String model, String provider, String providerModel) {
        return new StreamEvent(StreamEventType.START, traceId, 0L, model, provider, providerModel, null, null, null, null, null);
    }

    public static StreamEvent delta(String traceId, String delta) {
        return delta(traceId, 0L, null, null, null, delta);
    }

    public static StreamEvent delta(String traceId, long sequence, String model, String provider, String providerModel, String delta) {
        return new StreamEvent(StreamEventType.DELTA, traceId, sequence, model, provider, providerModel, delta, null, null, null, null);
    }

    public static StreamEvent usage(String traceId, long sequence, String model, String provider, String providerModel, Usage usage, CostInfo cost) {
        return new StreamEvent(StreamEventType.USAGE, traceId, sequence, model, provider, providerModel, null, null, usage, cost, null);
    }

    public static StreamEvent done(String traceId) {
        return done(traceId, 0L, null, null, null, "stop", null);
    }

    public static StreamEvent done(String traceId, long sequence, String model, String provider, String providerModel, String finishReason, Long totalMs) {
        return new StreamEvent(StreamEventType.DONE, traceId, sequence, model, provider, providerModel, null, finishReason, null, null, totalMs);
    }
}