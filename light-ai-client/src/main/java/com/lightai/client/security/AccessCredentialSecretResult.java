package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** 签发/轮换结果：token_value 仅本次返回，持久化为 HMAC-SHA256 摘要。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccessCredentialSecretResult(
        String credentialId,
        String tokenValue,
        String maskedValue,
        OffsetDateTime issuedAt,
        long rotationGeneration,
        long version) {
}
