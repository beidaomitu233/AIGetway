package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * 渠道管理命令（BACKEND_PLAN BE-211：provider_type、base_url、proxy、timeouts、
 * headers、priority、weight、version）。创建与编辑共用；编辑必须提交 version（乐观锁）；
 * provider_type 创建后不可变；配置状态不在此命令表达，启停走独立状态命令。
 * 校验边界与 DATABASE_PLAN channel 表一致；认证头在写入前拒绝。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelSaveCommand(
        String name,
        String providerType,
        String baseUrl,
        String proxy,
        ChannelTimeouts timeouts,
        Map<String, String> headers,
        Integer priority,
        Integer weight,
        Long version) {

    public static final int NAME_MIN = 2;
    public static final int NAME_MAX = 64;
    public static final int CONNECT_TIMEOUT_MIN = 100;
    public static final int CONNECT_TIMEOUT_MAX = 60000;
    public static final int READ_TIMEOUT_MIN = 1000;
    public static final int READ_TIMEOUT_MAX = 600000;
    public static final int MAX_HEADERS = 20;
    public static final int PRIORITY_MIN = 1;
    public static final int PRIORITY_MAX = 100;
    public static final int WEIGHT_MIN = 1;
    public static final int WEIGHT_MAX = 100;
    public static final int PRIORITY_DEFAULT = 10;
    public static final int WEIGHT_DEFAULT = 1;

    public ChannelSaveCommand {
        if (name == null || name.strip().length() < NAME_MIN || name.strip().length() > NAME_MAX) {
            throw new IllegalArgumentException("name 长度必须为 " + NAME_MIN + "—" + NAME_MAX);
        }
        if (providerType == null || providerType.isBlank() || providerType.length() > 64) {
            throw new IllegalArgumentException("provider_type 必填且不超过 64 字符");
        }
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.length() > 2048) {
            throw new IllegalArgumentException("base_url 必填且不超过 2048 字符");
        }
        if (proxy != null && proxy.isBlank()) {
            proxy = null;
        }
        if (proxy != null && proxy.length() > 2048) {
            throw new IllegalArgumentException("proxy 不超过 2048 字符");
        }
        if (timeouts == null) {
            throw new IllegalArgumentException("timeouts 必填");
        }
        if (timeouts.connectMs() < CONNECT_TIMEOUT_MIN || timeouts.connectMs() > CONNECT_TIMEOUT_MAX) {
            throw new IllegalArgumentException(
                    "timeouts.connect_ms 范围 " + CONNECT_TIMEOUT_MIN + "—" + CONNECT_TIMEOUT_MAX);
        }
        if (timeouts.readMs() < READ_TIMEOUT_MIN || timeouts.readMs() > READ_TIMEOUT_MAX
                || timeouts.readMs() < timeouts.connectMs()) {
            throw new IllegalArgumentException("timeouts.read_ms 范围 " + READ_TIMEOUT_MIN + "—"
                    + READ_TIMEOUT_MAX + " 且不少于连接超时");
        }
        if (timeouts.streamIdleMs() != null
                && (timeouts.streamIdleMs() < ChannelTimeouts.STREAM_IDLE_MIN_MS
                || timeouts.streamIdleMs() > ChannelTimeouts.STREAM_IDLE_MAX_MS)) {
            throw new IllegalArgumentException("timeouts.stream_idle_ms 范围 "
                    + ChannelTimeouts.STREAM_IDLE_MIN_MS + "—" + ChannelTimeouts.STREAM_IDLE_MAX_MS);
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("headers 最多 " + MAX_HEADERS + " 项");
        }
        if (priority != null && (priority < PRIORITY_MIN || priority > PRIORITY_MAX)) {
            throw new IllegalArgumentException("priority 范围 " + PRIORITY_MIN + "—" + PRIORITY_MAX);
        }
        if (weight != null && (weight < WEIGHT_MIN || weight > WEIGHT_MAX)) {
            throw new IllegalArgumentException("weight 范围 " + WEIGHT_MIN + "—" + WEIGHT_MAX);
        }
    }

    public String name() {
        return name == null ? null : name.strip();
    }

    public int priorityOrDefault() {
        return priority == null ? PRIORITY_DEFAULT : priority;
    }

    public int weightOrDefault() {
        return weight == null ? WEIGHT_DEFAULT : weight;
    }
}
