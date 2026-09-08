package com.lightai.client.application;

import java.time.OffsetDateTime;
import java.util.List;

/** 创建企业应用及其首个治理策略。null 额度表示该维度不设上限。 */
public record ApplicationCreateCommand(
        String code,
        String name,
        String department,
        String ownerId,
        String ownerName,
        String environment,
        String description,
        String status,
        Long tokenLimit,
        String amountLimit,
        String currency,
        Integer rpm,
        Long tpm,
        String periodType,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        List<String> virtualModelIds) {

    public ApplicationCreateCommand {
        virtualModelIds = virtualModelIds == null ? List.of() : List.copyOf(virtualModelIds);
    }
}
