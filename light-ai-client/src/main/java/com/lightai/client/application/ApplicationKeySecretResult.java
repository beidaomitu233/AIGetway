package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** 签发或轮换结果；keyValue 仅本次响应出现，之后无法找回。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationKeySecretResult(
        String keyId,
        String applicationId,
        String keyValue,
        String maskedValue,
        OffsetDateTime issuedAt,
        long rotationGeneration,
        long version) {
}
