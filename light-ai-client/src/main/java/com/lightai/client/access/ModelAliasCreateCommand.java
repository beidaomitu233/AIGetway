package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 新建 Model Alias 命令（4.2.9.4）：alias 2—64，字母数字点短横线下划线，创建后只读。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ModelAliasCreateCommand(
        String alias,
        String displayName,
        String description,
        Boolean enabled) {
}
