package com.lightai.storage.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 企业应用主记录。code 创建后保持稳定，status 控制新请求准入。 */
public record ApplicationRecord(
        UUID id,
        String code,
        String name,
        String department,
        String ownerId,
        String ownerName,
        String environment,
        String description,
        String status,
        OffsetDateTime lastCalledAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
