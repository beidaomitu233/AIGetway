package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 编辑 Model Alias 命令：即使提交 alias 也被拒绝（创建后不可变）。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ModelAliasUpdateCommand(
        String displayName,
        String description,
        Boolean enabled,
        long version) {
}
