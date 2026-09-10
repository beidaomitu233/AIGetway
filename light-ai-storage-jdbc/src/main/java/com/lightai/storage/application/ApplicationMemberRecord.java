package com.lightai.storage.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 应用成员关系，决定应用数据范围（PRD 7.1 application_member）。 */
public record ApplicationMemberRecord(
        UUID id,
        UUID applicationId,
        String subjectId,
        String subjectName,
        String role,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
