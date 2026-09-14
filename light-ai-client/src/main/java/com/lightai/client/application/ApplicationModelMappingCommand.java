package com.lightai.client.application;

import java.util.List;

/** 应用映射编辑行。id 可省略，服务端会为新行生成稳定标识。 */
public record ApplicationModelMappingCommand(
        String id,
        String publicModelName,
        String status,
        List<ApplicationModelTargetCommand> targets) {

    public ApplicationModelMappingCommand {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
