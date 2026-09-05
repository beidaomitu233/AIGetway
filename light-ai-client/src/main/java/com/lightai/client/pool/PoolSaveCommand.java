package com.lightai.client.pool;

import java.util.UUID;

/**
 * 凭证池管理命令（BACKEND_PLAN 4.2.9.2）：provider_id 创建后不可修改；
 * 编辑必须提交 version。
 */
public record PoolSaveCommand(
        UUID providerId,
        String name,
        SelectionStrategy selectionStrategy,
        boolean enabled,
        Long version) {

    public static final int NAME_MIN = 2;
    public static final int NAME_MAX = 64;

    public PoolSaveCommand {
        if (providerId == null) {
            throw new IllegalArgumentException("provider_id 必填");
        }
        if (name == null || name.strip().length() < NAME_MIN || name.strip().length() > NAME_MAX) {
            throw new IllegalArgumentException("name 长度必须为 " + NAME_MIN + "—" + NAME_MAX);
        }
        if (selectionStrategy == null) {
            throw new IllegalArgumentException("selection_strategy 必填");
        }
    }

    public String name() {
        return name == null ? null : name.strip();
    }
}
