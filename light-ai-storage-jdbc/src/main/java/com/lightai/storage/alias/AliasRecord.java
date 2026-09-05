package com.lightai.storage.alias;

import java.time.OffsetDateTime;
import java.util.UUID;

/** model_alias 表行（DATABASE_PLAN §6，存储类别 C）。alias 创建后不变。 */
public record AliasRecord(
        UUID id,
        String alias,
        String displayName,
        String description,
        String routeStrategy,
        boolean enabled,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
