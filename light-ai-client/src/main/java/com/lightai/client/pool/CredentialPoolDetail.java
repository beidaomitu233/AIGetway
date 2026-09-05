package com.lightai.client.pool;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 凭证池详情（BACKEND_PLAN 4.2.9.2；字段对齐 FE-012）。
 * created_by/updated_by 当前来自 draft_change.modified_by 摘要（见 ProviderDetail 同款说明）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CredentialPoolDetail(
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
        long routeCandidateCount,
        long modelAliasCount,
        String createdBy,
        OffsetDateTime createdAt,
        String updatedBy,
        OffsetDateTime updatedAt) {
}
