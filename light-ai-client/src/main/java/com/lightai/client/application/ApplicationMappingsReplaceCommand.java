package com.lightai.client.application;

import java.util.List;

/** 按应用版本完整替换模型映射集合。 */
public record ApplicationMappingsReplaceCommand(
        long applicationVersion,
        List<ApplicationModelMappingCommand> mappings,
        String reason) {

    public ApplicationMappingsReplaceCommand {
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }
}
