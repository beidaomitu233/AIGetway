package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 差异字段展示视图（前端 config.ts FieldChange 契约）。
 * sensitive=true 时 before/after 恒为 null，仅提示字段已变更（4.5.1.4 脱敏规则）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FieldChangeView(
        String field,
        String beforeValue,
        String afterValue,
        boolean sensitive) {
}
