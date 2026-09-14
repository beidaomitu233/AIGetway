package com.lightai.storage.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ApplicationModelMappingRecord(
        UUID id,
        UUID applicationId,
        UUID revisionId,
        UUID virtualModelId,
        String publicModelName,
        String status,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ApplicationModelTargetRecord> targets) {

    public ApplicationModelMappingRecord {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
