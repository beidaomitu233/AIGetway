package com.lightai.storage.alias;

import java.time.OffsetDateTime;
import java.util.UUID;

/** route_candidate 表行（DATABASE_PLAN §7，存储类别 C）。三元组活行唯一。 */
public record CandidateRecord(
        UUID id,
        UUID aliasId,
        UUID providerModelId,
        UUID credentialPoolId,
        int priority,
        int weight,
        boolean enabled,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
