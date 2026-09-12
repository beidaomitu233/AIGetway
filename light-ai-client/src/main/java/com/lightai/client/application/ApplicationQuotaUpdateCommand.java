package com.lightai.client.application;

import java.time.OffsetDateTime;

/**
 * 更新应用额度与速率策略；null 上限表示该维度不限额。
 * 允许把上限降到已用+预占以下（BE-P20-004/PRD 4.5）：保存成功并立即阻止新准入。
 * idempotency_key 以 dimension=POLICY 记入 quota_adjustment 变更流水。
 */
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
        String reason,
        String idempotencyKey) {
}
