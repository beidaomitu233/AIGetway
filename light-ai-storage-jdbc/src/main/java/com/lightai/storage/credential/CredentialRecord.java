package com.lightai.storage.credential;

import java.time.OffsetDateTime;
import java.util.UUID;

/** credential 表行（DATABASE_PLAN §3，存储类别 C）。secret_source 创建后不可变。 */
public record CredentialRecord(
        UUID id,
        UUID poolId,
        String name,
        String secretSource,
        int weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        boolean enabled,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
