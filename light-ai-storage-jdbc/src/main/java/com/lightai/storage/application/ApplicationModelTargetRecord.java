package com.lightai.storage.application;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ApplicationModelTargetRecord(
        UUID id,
        UUID mappingId,
        UUID channelId,
        UUID upstreamModelId,
        String upstreamModelName,
        int priority,
        int weight,
        String status,
        String policyJson,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
