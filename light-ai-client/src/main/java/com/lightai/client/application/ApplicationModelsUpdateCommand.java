package com.lightai.client.application;

import java.util.List;

/**
 * 以目标集合替换应用的虚拟模型授权，并同时提交每个模型的可选请求参数上限。
 * constraints 只需包含配置了上限的模型；未出现的模型视为无上限。
 */
public record ApplicationModelsUpdateCommand(
        List<String> virtualModelIds,
        List<ApplicationModelConstraint> constraints,
        long applicationVersion,
        String reason) {

    public ApplicationModelsUpdateCommand {
        virtualModelIds = virtualModelIds == null ? List.of() : List.copyOf(virtualModelIds);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }
}
