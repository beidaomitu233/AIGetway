package com.lightai.storage.alias;

import java.time.OffsetDateTime;
import java.util.UUID;

/** route_candidate 表行（DATABASE_PLAN §7，C类）。 */
public record RouteCandidateRecord(
        UUID id,
        UUID aliasId,
        UUID providerModelId,
        UUID credentialPoolId,
        int priority,
        int weight,
        boolean enabled,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {

    public boolean alive() {
        return deletedAt == null;
    }
}
