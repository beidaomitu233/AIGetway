package com.lightai.client.application;

/** 编辑应用基本信息。应用 code 和治理策略使用独立接口，不在此修改。 */
public record ApplicationUpdateCommand(
        String name,
        String department,
        String ownerId,
        String ownerName,
        String environment,
        String description,
        long version) {
}
