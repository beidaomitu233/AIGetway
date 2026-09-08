package com.lightai.storage.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** quota_adjustment 追加流水。 */
public record QuotaAdjustmentRecord(
        UUID id,
        UUID applicationId,
        String dimension,
        BigDecimal beforeValue,
        BigDecimal deltaValue,
        BigDecimal afterValue,
        String reason,
        OffsetDateTime effectiveAt,
        String operatorId,
        String idempotencyKey,
        OffsetDateTime createdAt) {
}
