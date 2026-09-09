package com.lightai.client.application;

/** 高风险应用用量重置；确认文本必须与应用编码完全一致。 */
public record ApplicationQuotaResetCommand(
        String dimension,
        String reason,
        String confirmationCode,
        String idempotencyKey,
        long quotaVersion) {
}
