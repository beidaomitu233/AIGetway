package com.lightai.client.alias;

/**
 * Alias 保存命令（BE-016）：alias 创建后只读（更新命令不含该字段）；
 * 命名 2—64，仅字母数字点横线下划线；无候选草稿可保存，发布/启用时严格拦截。
 */
public record ModelAliasSaveCommand(
        String alias,
        String displayName,
        String description,
        boolean enabled,
        Long version) {

    public static final int ALIAS_MIN = 2;
    public static final int ALIAS_MAX = 64;
    public static final int DISPLAY_NAME_MAX = 64;
    public static final int DESCRIPTION_MAX = 500;

    public ModelAliasSaveCommand {
        if (alias != null && !alias.matches("[A-Za-z0-9._\\-]{2,64}")) {
            throw new IllegalArgumentException("alias 为 2—64 位字母数字点横线下划线");
        }
        if (displayName != null && (displayName.strip().length() < 2
                || displayName.strip().length() > DISPLAY_NAME_MAX)) {
            throw new IllegalArgumentException("display_name 长度必须为 2—" + DISPLAY_NAME_MAX);
        }
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException("description 最长 " + DESCRIPTION_MAX);
        }
    }
}
