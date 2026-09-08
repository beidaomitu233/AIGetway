package com.lightai.client.application;

import java.time.OffsetDateTime;

/** 更新应用额度与速率策略；null 上限表示该维度不限额。 */
public record ApplicationQuotaUpdateCommand(
        Long tokenLimit,
        String amountLimit,
        String currency,
        Integer rpm,
        Long tpm,
        String periodType,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        long version,
        String reason) {
}
