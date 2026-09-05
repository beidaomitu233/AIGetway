package com.lightai.spi.auth;

import java.util.Map;

/**
 * 身份适配输入：宿主/部署认证适配据此解析管理身份。
 * headers 只读传递；实现方不得把 Token、认证头写入日志或审计。
 */
public record AuthRequest(
        String method,
        String path,
        Map<String, String> headers,
        String remoteAddress) {

    public AuthRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
