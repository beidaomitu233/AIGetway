package com.lightai.client.credential;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** Credential 详情（BACKEND_PLAN 4.2.9.2；不含 secret_value 与 token_hash）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CredentialDetail(
        String id,
        String poolId,
        String name,
        String maskedValue,
        String secretRefDisplay,
        String secretSource,
        int weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        long currentConcurrency,
        String healthStatus,
        OffsetDateTime rateLimitResetAt,
        OffsetDateTime lastSuccessAt,
        OffsetDateTime lastCheckAt,
        boolean enabled,
        boolean draftChanged,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
