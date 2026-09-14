package com.lightai.client.application;

import java.util.List;

/** 从渠道模型目录生成同名映射草案。 */
public record ApplicationMappingsBulkCreateCommand(
        List<String> channelIds,
        String query,
        Integer limit) {

    public ApplicationMappingsBulkCreateCommand {
        channelIds = channelIds == null ? List.of() : List.copyOf(channelIds);
    }
}
