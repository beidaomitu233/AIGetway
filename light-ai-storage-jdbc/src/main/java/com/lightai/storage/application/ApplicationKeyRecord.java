package com.lightai.storage.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** application_key 表行；密钥只保留 HMAC 摘要与不可反推掩码。 */
public record ApplicationKeyRecord(
        UUID id,
        UUID applicationId,
        String name,
        String keyPrefix,
        String maskedValue,
        byte[] keyDigest,
        int digestVersion,
        long rotationGeneration,
        List<String> ipAllowlist,
        OffsetDateTime expiresAt,
        Integer rpm,
        Long tpm,
        String status,
        OffsetDateTime lastUsedAt,
        String lastUsedIpMasked,
        OffsetDateTime rotatedAt,
        OffsetDateTime revokedAt,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public ApplicationKeyRecord {
        keyDigest = keyDigest == null ? null : keyDigest.clone();
        ipAllowlist = ipAllowlist == null ? List.of() : List.copyOf(ipAllowlist);
    }

    @Override
    public byte[] keyDigest() {
        return keyDigest == null ? null : keyDigest.clone();
    }

    public boolean expired(OffsetDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean active(OffsetDateTime now) {
        return "ACTIVE".equals(status) && revokedAt == null && !expired(now);
    }

    public String effectiveStatus(OffsetDateTime now) {
        return "ACTIVE".equals(status) && expired(now) ? "EXPIRED" : status;
    }
}
