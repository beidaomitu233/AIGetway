package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/** 应用中心列表行。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationListItem(
        String id,
        String code,
        String name,
        String department,
        String ownerId,
        String ownerName,
        String environment,
        String status,
        long modelCount,
        long activeKeyCount,
        Long tokenLimit,
        long tokensUsed,
        long tokensReserved,
        String amountLimit,
        String amountUsed,
        String amountReserved,
        String currency,
        Integer rpm,
        Long tpm,
        OffsetDateTime lastCalledAt,
        OffsetDateTime updatedAt,
        long version) {
}
