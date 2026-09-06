package com.lightai.storage.access;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * access_credential 表行（DATABASE_PLAN §36，S类即时实体）。
 * 不存 token_value：token_hash 为 HMAC-SHA256 摘要；EXPIRED 由读取按 expires_at 计算。
 */
public record AccessCredentialRecord(
        UUID id,
        String name,
        String application,
        String tokenPrefix,
        byte[] tokenHash,
        int tokenHashVersion,
        String maskedValue,
        List<String> ipAllowlist,
        OffsetDateTime expiresAt,
        boolean enabled,
        long rotationGeneration,
        OffsetDateTime issuedAt,
        OffsetDateTime rotatedAt,
        OffsetDateTime lastUsedAt,
        String lastUsedIpMasked,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_DELETED = "DELETED";

    public boolean alive() {
        return deletedAt == null;
    }

    /** 状态派生：DELETED > EXPIRED（读取计算）> DISABLED > ACTIVE。 */
    public String status(OffsetDateTime now) {
        if (!alive()) {
            return STATUS_DELETED;
        }
        if (expiresAt != null && expiresAt.isBefore(now)) {
            return STATUS_EXPIRED;
        }
        return enabled ? STATUS_ACTIVE : STATUS_DISABLED;
    }

    /** 已过期凭证不能重新启用（ACCESS_CREDENTIAL_EXPIRED）。 */
    public boolean expired(OffsetDateTime now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
