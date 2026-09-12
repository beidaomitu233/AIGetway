package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 签发或轮换结果；secret 仅本次响应出现，之后无法找回（BE-P20-002）。
 * 轮换新代际、宽限与幂等重放依赖 DB-202 迁移，交付前轮换仍为原位换发。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationKeySecretResult(
        String keyId,
        String applicationId,
        String secret,
        String keyPrefix,
        String maskedValue,
        String status,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        long rotationGeneration,
        long version) {
}
