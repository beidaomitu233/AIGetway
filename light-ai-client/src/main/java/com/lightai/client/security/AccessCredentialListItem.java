package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** Access Credential 列表项（4.5.6.3）；不含 token_value 与 token_hash。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccessCredentialListItem(
        String id,
        String name,
        String application,
        String status,
        String maskedValue,
        List<String> allowedAliasIds,
        Integer ipAllowlistCount,
        OffsetDateTime expiresAt,
        OffsetDateTime lastUsedAt,
        long rotationGeneration,
        Boolean draftChanged,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {
}
