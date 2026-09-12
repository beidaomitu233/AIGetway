package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 渠道超时配置（BACKEND_PLAN BE-211：timeouts 字段）。
 * connect/read 必填；stream_idle 可选，缺省沿用迁移默认值 120000。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChannelTimeouts(int connectMs, int readMs, Integer streamIdleMs) {

    public static final int STREAM_IDLE_DEFAULT_MS = 120000;
    public static final int STREAM_IDLE_MIN_MS = 1000;
    public static final int STREAM_IDLE_MAX_MS = 600000;

    public int streamIdleMsOrDefault() {
        return streamIdleMs == null ? STREAM_IDLE_DEFAULT_MS : streamIdleMs;
    }
}
