package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 新增候选命令（4.2.9.4）：model 与 pool 必须同 Provider，重复三元组拒绝。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RouteCandidateCreateCommand(
        String providerModelId,
        String credentialPoolId,
        Integer priority,
        Integer weight,
        Boolean enabled) {
}
