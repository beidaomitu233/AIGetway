package com.lightai.client.changes;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 草稿差异与审计共用的字段变更条目（DATABASE_PLAN 第 3 节 JSON 字典）。
 * 敏感字段只保留 field_path 与 changed=true，before/after 不落值、不落引用；
 * 脱敏在写入前由审计/差异服务完成，本类型不做语义判断。
 * before/after 仅允许非敏感 JSON 标量或数组（字符串/数值/布尔/列表）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldChange(
        String fieldPath,
        Object before,
        Object after,
        boolean changed) {

    public FieldChange {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new IllegalArgumentException("field_path 必填");
        }
        if (fieldPath.length() > 256) {
            throw new IllegalArgumentException("field_path 超过 256 字符");
        }
    }

    public static FieldChange changed(String fieldPath, Object before, Object after) {
        return new FieldChange(fieldPath, before, after, true);
    }

    /** 敏感字段条目：不携带任何值或引用。 */
    public static FieldChange sensitiveChanged(String fieldPath) {
        return new FieldChange(fieldPath, null, null, true);
    }
}
