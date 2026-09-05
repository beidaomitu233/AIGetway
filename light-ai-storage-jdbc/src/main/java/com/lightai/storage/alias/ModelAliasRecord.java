package com.lightai.storage.alias;

import java.time.OffsetDateTime;
import java.util.UUID;

/** model_alias 表行（DATABASE_PLAN §6，C类）；alias 创建后不可变。 */
public record ModelAliasRecord(
        UUID id,
        String alias,
        String displayName,
        String description,
        String routeStrategy,
        boolean enabled,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {

    public boolean alive() {
        return deletedAt == null;
    }
}
