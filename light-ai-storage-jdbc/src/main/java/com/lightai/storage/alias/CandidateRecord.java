package com.lightai.storage.alias;

import java.time.OffsetDateTime;
import java.util.UUID;

/** route_candidate 表行（DATABASE_PLAN §7，存储类别 C；V5 起 alias_id 列更名 virtual_model_id，record 命名保留 aliasId 以稳定调用方）。三元组活行唯一。 */
public record CandidateRecord(
        UUID id,
        UUID aliasId,
        UUID upstreamModelId,
        UUID channelId,
        int priority,
        int weight,
        boolean enabled,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
