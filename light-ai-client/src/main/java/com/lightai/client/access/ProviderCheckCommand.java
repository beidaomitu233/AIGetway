package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 检测命令（BACKEND_PLAN 2 协议字典）。
 * model_id 与 provider_model_id 必须解析到同一 Provider；credential_id 可空时由目标池选择；
 * timeout_ms 默认 10000，范围 100—60000；不接收真实 Secret。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProviderCheckCommand(
        String modelId,
        String providerModelId,
        String credentialId,
        String mode,
        Integer timeoutMs) {

    public static final int DEFAULT_TIMEOUT_MS = 10000;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 60000;
}
