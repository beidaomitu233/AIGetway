package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** Credential 详情（BACKEND_PLAN BE-212；不含 secret_value 与 token_hash）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChannelCredentialDetail(
        String id,
        String channelId,
        String name,
        String maskedValue,
        String secretRefDisplay,
        String secretSource,
        int priority,
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
