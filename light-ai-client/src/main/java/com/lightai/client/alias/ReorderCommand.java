package com.lightai.client.alias;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 候选原子重排命令（BE-018）：必须提供当前 Alias 下完整候选集合且无重复 id，
 * 每项携带自身 version；全部核对通过后统一写入，任一冲突整体失败。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReorderCommand(List<ReorderItem> items) {

    public record ReorderItem(String id, Integer priority, Long version) {
    }

    public ReorderCommand {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public void validate() {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items 不能为空");
        }
        long distinct = items.stream().map(ReorderItem::id).distinct().count();
        if (distinct != items.size()) {
            throw new IllegalArgumentException("items 存在重复候选 id");
        }
        for (ReorderItem item : items) {
            if (item.id() == null || item.id().isBlank()) {
                throw new IllegalArgumentException("items[].id 必填");
            }
            if (item.priority() == null || item.priority() < 1 || item.priority() > 100) {
                throw new IllegalArgumentException("items[].priority 范围 1—100");
            }
            if (item.version() == null || item.version() < 1) {
                throw new IllegalArgumentException("items[].version 必填");
            }
        }
    }
}
