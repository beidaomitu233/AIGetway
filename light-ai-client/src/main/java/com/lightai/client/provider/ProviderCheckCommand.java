package com.lightai.client.provider;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Provider 检测命令（BACKEND_PLAN 2 协议字典）：
 * model_id 或 provider_model_id 必须解析到同一 Provider；
 * credential_id 可空（由目标池选择）；不接收真实 Secret。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderCheckCommand(
        String modelId,
        String providerModelId,
        String credentialId,
        String mode,
        Integer timeoutMs) {

    public static final String MODE_MINIMAL_CHAT = "MINIMAL_CHAT";
    public static final String MODE_CONNECTION_ONLY = "CONNECTION_ONLY";
    public static final int TIMEOUT_DEFAULT_MS = 10000;
    public static final int TIMEOUT_MIN_MS = 100;
    public static final int TIMEOUT_MAX_MS = 60000;

    public boolean hasModelTarget() {
        return (modelId != null && !modelId.isBlank()) || (providerModelId != null && !providerModelId.isBlank());
    }

    public boolean hasBothModelTargets() {
        return modelId != null && !modelId.isBlank()
                && providerModelId != null && !providerModelId.isBlank();
    }

    public String resolvedMode() {
        return mode == null || mode.isBlank() ? MODE_MINIMAL_CHAT : mode;
    }

    public int resolvedTimeoutMs() {
        return timeoutMs == null ? TIMEOUT_DEFAULT_MS : timeoutMs;
    }
}
