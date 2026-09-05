package com.lightai.client.access;

import java.time.OffsetDateTime;

/**
 * Credential 详情（4.2.9.2）：响应不含 secret_value、token_hash 与已解析外部 Secret；
 * EXTERNAL_REF 的完整引用同样不回显，仅提供非敏感掩码。
 */
public record CredentialDetail(
        String id,
        String poolId,
        String name,
        String secretSource,
        String maskedValue,
        Integer weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        Boolean enabled,
        String healthStatus,
        OffsetDateTime rateLimitResetAt,
        OffsetDateTime lastSuccessAt,
        OffsetDateTime lastCheckAt,
        OffsetDateTime lastFailedAt,
        String lastErrorCode,
        String lastErrorSummary,
        Boolean draftChanged,
        long secretVersion,
        OffsetDateTime rotatedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {
}
