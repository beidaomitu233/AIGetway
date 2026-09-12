package com.lightai.client.application;

import java.util.List;

/** 应用变更影响预览请求体（FE-P20 补充契约）。action 为 STATUS_CHANGE 或 MODEL_PERMISSION_CHANGE。 */
public record ApplicationImpactCommand(
        long version,
        String action,
        String targetStatus,
        List<String> removedModelIds) {
}
