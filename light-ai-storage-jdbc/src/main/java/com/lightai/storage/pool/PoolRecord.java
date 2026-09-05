package com.lightai.storage.pool;

import java.time.OffsetDateTime;
import java.util.UUID;

/** credential_pool 表行（DATABASE_PLAN §2，存储类别 C）。provider_id 创建后不变。 */
public record PoolRecord(
        UUID id,
        UUID providerId,
        String name,
        String selectionStrategy,
        boolean enabled,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
