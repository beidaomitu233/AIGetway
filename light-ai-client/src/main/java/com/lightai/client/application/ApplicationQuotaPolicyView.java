package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 应用当前治理策略及实时账面占用。金额与 64 位 Token 数值使用十进制字符串传输（FE-P20 补充契约）。
 * tokens_remaining/amount_remaining = max(0, limit-used-reserved)，无限额返回 null（BE-P20-004）。
 * period_id/policy_version/timezone/reset_at 依赖 DB-203 周期快照与平台时区配置，就绪前为 null。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationQuotaPolicyView(
        String id,
        String tokenLimit,
        String tokensUsed,
        String tokensReserved,
        String amountLimit,
        String amountUsed,
        String amountReserved,
        String currency,
        Integer rpm,
        Long tpm,
        String periodType,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        String periodId,
        String policyVersion,
        String timezone,
        OffsetDateTime resetAt,
        String tokensRemaining,
        String amountRemaining,
        boolean admissionBlocked,
        String version) {
}
