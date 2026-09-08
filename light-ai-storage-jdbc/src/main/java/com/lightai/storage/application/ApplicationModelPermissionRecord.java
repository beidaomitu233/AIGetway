package com.lightai.storage.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 应用与虚拟模型的授权关系。 */
public record ApplicationModelPermissionRecord(
        UUID id,
        UUID applicationId,
        UUID virtualModelId,
        String virtualModelCode,
        boolean enabled,
        String constraintsJson,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
