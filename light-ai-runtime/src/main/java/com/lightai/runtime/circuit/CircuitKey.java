package com.lightai.runtime.circuit;

import java.time.Instant;
import java.util.UUID;

/**
 * 熔断键（C-008）：upstream_model_id + channel_credential_id 共享同一路径健康窗口。
 */
public record CircuitKey(UUID upstreamModelId, UUID channelCredentialId) {
}
