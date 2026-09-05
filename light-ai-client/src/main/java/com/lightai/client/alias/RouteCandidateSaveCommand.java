package com.lightai.client.alias;

import java.util.UUID;

/**
 * 候选保存命令（BE-017）：model 与 pool 必须同 Provider；
 * (alias, provider_model, credential_pool) 三元组重复返回 DUPLICATE_ROUTE_CANDIDATE；
 * 更新不换 model。
 */
public record RouteCandidateSaveCommand(
        UUID providerModelId,
        UUID credentialPoolId,
        Integer priority,
        Integer weight,
        Boolean enabled,
        Long version) {

    public static final int PRIORITY_MIN = 1;
    public static final int PRIORITY_MAX = 100;
    public static final int WEIGHT_MIN = 1;
    public static final int WEIGHT_MAX = 100;

    public void validateForCreate() {
        if (providerModelId == null || credentialPoolId == null) {
            throw new IllegalArgumentException("provider_model_id 与 credential_pool_id 必填");
        }
        validatePriorityWeight();
    }

    public void validatePriorityWeight() {
        if (priority != null && (priority < PRIORITY_MIN || priority > PRIORITY_MAX)) {
            throw new IllegalArgumentException("priority 范围 1—100");
        }
        if (weight != null && (weight < WEIGHT_MIN || weight > WEIGHT_MAX)) {
            throw new IllegalArgumentException("weight 范围 1—100");
        }
    }
}
