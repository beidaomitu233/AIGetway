package com.lightai.client.application;

import java.util.List;

/** 保存前校验模型映射，不写入数据库。 */
public record ApplicationMappingsValidateCommand(
        long applicationVersion,
        List<ApplicationModelMappingCommand> mappings) {

    public ApplicationMappingsValidateCommand {
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
    }
}
