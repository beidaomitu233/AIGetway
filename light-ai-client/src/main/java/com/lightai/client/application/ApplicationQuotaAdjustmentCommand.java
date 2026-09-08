package com.lightai.client.application;

/** 幂等增加或扣减应用 Token/金额上限。 */
public record ApplicationQuotaAdjustmentCommand(
        String dimension,
        String delta,
        String reason,
        String idempotencyKey,
        long quotaVersion) {
}
