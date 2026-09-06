package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** Access Credential 详情；永不返回 token_value/token_hash。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccessCredentialDetail(
        String id,
        String name,
        String application,
        String status,
        String maskedValue,
        List<String> allowedAliasIds,
        List<String> ipAllowlist,
        OffsetDateTime expiresAt,
        boolean enabled,
        long rotationGeneration,
        OffsetDateTime issuedAt,
        OffsetDateTime rotatedAt,
        OffsetDateTime lastUsedAt,
        String lastUsedIpMasked,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {
}
