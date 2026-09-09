package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** 应用密钥的非敏感视图，不包含摘要或密钥原文。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationKeyView(
        String id,
        String applicationId,
        String name,
        String maskedValue,
        List<String> ipAllowlist,
        OffsetDateTime expiresAt,
        Integer rpm,
        Long tpm,
        String status,
        OffsetDateTime lastUsedAt,
        String lastUsedIpMasked,
        OffsetDateTime issuedAt,
        OffsetDateTime rotatedAt,
        OffsetDateTime revokedAt,
        long rotationGeneration,
        long version,
        List<String> virtualModelIds) {

    public ApplicationKeyView {
        ipAllowlist = ipAllowlist == null ? List.of() : List.copyOf(ipAllowlist);
        virtualModelIds = virtualModelIds == null ? List.of() : List.copyOf(virtualModelIds);
    }
}
