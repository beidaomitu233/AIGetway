package com.lightai.client.credential;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Credential 列表项（BACKEND_PLAN 4.2.9.2；字段对齐 FE-013）。
 * 响应只含 masked_value/secret_source/secret_ref_display，
 * secret_value 与 token_hash 永不出现在任何响应中。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CredentialListItem(
        String id,
        String poolId,
        String name,
        String maskedValue,
        String secretRefDisplay,
        String secretSource,
        int weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        long currentConcurrency,
        String healthStatus,
        OffsetDateTime rateLimitResetAt,
        OffsetDateTime lastSuccessAt,
        OffsetDateTime lastCheckAt,
        boolean enabled,
        boolean draftChanged,
        long version) {

    public static final String SOURCE_INLINE = "INLINE_ENCRYPTED";
    public static final String SOURCE_EXTERNAL = "EXTERNAL_REF";
}
