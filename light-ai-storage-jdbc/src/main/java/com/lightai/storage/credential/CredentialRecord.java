package com.lightai.storage.credential;

import java.time.OffsetDateTime;
import java.util.UUID;

/** credential 表行（DATABASE_PLAN §3，C类草稿实体）；不承载任何密钥内容。 */
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
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {

    /** 草稿活行判定：deleted_at IS NULL。 */
    public boolean alive() {
        return deletedAt == null;
    }
}
