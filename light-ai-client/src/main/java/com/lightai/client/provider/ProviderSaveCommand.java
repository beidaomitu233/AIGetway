package com.lightai.client.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Provider 管理命令（BACKEND_PLAN 4.2.9.1）：创建与编辑共用。
 * 编辑必须提交 version（乐观锁）；type 创建后不可变。
 * 校验边界与 DATABASE_PLAN provider 表一致；认证头在写入前拒绝。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderSaveCommand(
        String name,
        String type,
        String baseUrl,
        String proxyUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        Map<String, String> defaultHeaders,
        boolean enabled,
        Long version) {

    public static final int NAME_MIN = 2;
    public static final int NAME_MAX = 64;
    public static final int CONNECT_TIMEOUT_MIN = 100;
    public static final int CONNECT_TIMEOUT_MAX = 60000;
    public static final int READ_TIMEOUT_MIN = 1000;
    public static final int READ_TIMEOUT_MAX = 600000;
    public static final int MAX_HEADERS = 20;

    public ProviderSaveCommand {
        if (name == null || name.strip().length() < NAME_MIN || name.strip().length() > NAME_MAX) {
            throw new IllegalArgumentException("name 长度必须为 " + NAME_MIN + "—" + NAME_MAX);
        }
        if (type == null || type.isBlank() || type.length() > 64) {
            throw new IllegalArgumentException("type 必填且不超过 64 字符");
        }
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.length() > 2048) {
            throw new IllegalArgumentException("base_url 必填且不超过 2048 字符");
        }
        if (proxyUrl != null && proxyUrl.isBlank()) {
            proxyUrl = null;
        }
        if (proxyUrl != null && proxyUrl.length() > 2048) {
            throw new IllegalArgumentException("proxy_url 不超过 2048 字符");
        }
        if (connectTimeoutMs < CONNECT_TIMEOUT_MIN || connectTimeoutMs > CONNECT_TIMEOUT_MAX) {
            throw new IllegalArgumentException(
                    "connect_timeout_ms 范围 " + CONNECT_TIMEOUT_MIN + "—" + CONNECT_TIMEOUT_MAX);
        }
        if (readTimeoutMs < READ_TIMEOUT_MIN || readTimeoutMs > READ_TIMEOUT_MAX
                || readTimeoutMs < connectTimeoutMs) {
            throw new IllegalArgumentException("read_timeout_ms 范围 " + READ_TIMEOUT_MIN + "—"
                    + READ_TIMEOUT_MAX + " 且不少于连接超时");
        }
        defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        if (defaultHeaders.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("default_headers 最多 " + MAX_HEADERS + " 项");
        }
    }

    public String name() {
        return name == null ? null : name.strip();
    }
}
