package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 编辑候选命令：provider_model_id 不可更换，仅池/优先级/权重/启停可改。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RouteCandidateUpdateCommand(
        String credentialPoolId,
        Integer priority,
        Integer weight,
        Boolean enabled,
        long version) {
}
