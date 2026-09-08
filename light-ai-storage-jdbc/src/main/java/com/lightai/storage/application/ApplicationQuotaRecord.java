package com.lightai.storage.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 应用 Token、金额、RPM 与 TPM 的当前策略记录。 */
public record ApplicationQuotaRecord(
        UUID id,
        UUID applicationId,
        Long tokenLimit,
        BigDecimal amountLimit,
        String currency,
        Integer rpm,
        Long tpm,
        String periodType,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        long tokensUsed,
        long tokensReserved,
        BigDecimal amountUsed,
        BigDecimal amountReserved,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
