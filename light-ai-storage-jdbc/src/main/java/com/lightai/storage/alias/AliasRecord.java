package com.lightai.storage.alias;

import java.time.OffsetDateTime;
import java.util.UUID;

/** virtual_model 表行（DATABASE_PLAN §6，存储类别 C；V5 起物理表与 code 列更名，record 命名保留 alias 以稳定调用方）。alias 创建后不变。 */
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
