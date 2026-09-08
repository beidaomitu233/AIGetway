package com.lightai.client.application;

import java.util.List;

/** 以目标集合替换应用的虚拟模型授权。 */
public record ApplicationModelsUpdateCommand(
        List<String> virtualModelIds,
        long applicationVersion,
        String reason) {

    public ApplicationModelsUpdateCommand {
        virtualModelIds = virtualModelIds == null ? List.of() : List.copyOf(virtualModelIds);
    }
}
