package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 应用中心列表行。64 位 Token 数值、请求计数与 version 使用十进制字符串传输（FE-P20 补充契约）。
 * budget_status：任一有限维度 used+reserved>=limit 为 EXHAUSTED，两维都无限制为 UNLIMITED，其余 NORMAL。
 * success_rate_24h 为 0～1 十进制字符串，窗口内无终态请求为 null（BE-P20-001）。
 */
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
        String tokenLimit,
        String tokensUsed,
        String tokensReserved,
        String amountLimit,
        String amountUsed,
        String amountReserved,
        String currency,
        Integer rpm,
        Long tpm,
        String budgetStatus,
        String requests24h,
        String successRate24h,
        OffsetDateTime lastCalledAt,
        OffsetDateTime updatedAt,
        String version) {
}
