package com.lightai.storage.security;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * retention_impact 仓储端口（DATABASE_PLAN §39，I类）：票据绑定目标值与草稿修订，10 分钟有效。
 */
public interface RetentionImpactRepository {

    void insert(Connection connection, RetentionImpactRecord record);

    Optional<RetentionImpactRecord> find(Connection connection, UUID impactVersion);

    record RetentionImpactRecord(
            UUID id,
            UUID impactVersion,
            long draftRevision,
            String targetValuesJson,
            String countsJson,
            java.time.OffsetDateTime estimatedAt,
            java.time.OffsetDateTime expiresAt,
            String estimatedBy) {
    }
}
