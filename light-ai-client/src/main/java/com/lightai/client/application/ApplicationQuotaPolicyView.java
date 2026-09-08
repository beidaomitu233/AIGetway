package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** 应用当前治理策略及实时账面占用。金额使用十进制字符串传输。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationQuotaPolicyView(
        String id,
        Long tokenLimit,
        long tokensUsed,
        long tokensReserved,
        String amountLimit,
        String amountUsed,
        String amountReserved,
        String currency,
        Integer rpm,
        Long tpm,
        String periodType,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        long version) {
}
