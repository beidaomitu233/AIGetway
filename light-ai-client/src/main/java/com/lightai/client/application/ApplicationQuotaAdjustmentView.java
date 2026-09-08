package com.lightai.client.application;

import java.time.OffsetDateTime;

/** 应用额度调整或周期重置流水；数值使用十进制字符串传输。 */
public record ApplicationQuotaAdjustmentView(
        String id,
        String applicationId,
        String dimension,
        String beforeValue,
        String deltaValue,
        String afterValue,
        String reason,
        OffsetDateTime effectiveAt,
        String operatorId,
        OffsetDateTime createdAt) {
}
