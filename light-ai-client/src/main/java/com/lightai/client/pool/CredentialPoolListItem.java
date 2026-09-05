package com.lightai.client.pool;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 凭证池列表项（BACKEND_PLAN 4.2.9.2；字段对齐 FE-011）。
 * status 由 enabled 与池内 Credential 健康计数派生（DATABASE_PLAN 第 3 节），
 * current_concurrency/rpm_used/tpm_used 来自容量运行时，非草稿数据。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CredentialPoolListItem(
        String id,
        String providerId,
        String providerName,
        String name,
        String selectionStrategy,
        long credentialTotal,
        long credentialAvailable,
        long currentConcurrency,
        long rpmUsed,
        long tpmUsed,
        String status,
        boolean enabled,
        boolean draftChanged,
        long version,
        OffsetDateTime updatedAt) {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_PARTIAL_AVAILABLE = "PARTIAL_AVAILABLE";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATUS_DISABLED = "DISABLED";

    /** 池运行状态派生：enabled 优先，健康计数决定可用度。 */
    public static String deriveStatus(boolean enabled, long total, long healthy) {
        if (!enabled) {
            return STATUS_DISABLED;
        }
        if (total <= 0 || healthy <= 0) {
            return STATUS_UNAVAILABLE;
        }
        return healthy >= total ? STATUS_AVAILABLE : STATUS_PARTIAL_AVAILABLE;
    }
}
