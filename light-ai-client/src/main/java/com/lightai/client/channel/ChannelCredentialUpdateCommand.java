package com.lightai.client.channel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Credential 编辑命令（BACKEND_PLAN BE-212：Key 级 priority/weight 可编辑）。
 * secret_source 不可变；secret_ref 显式 null 不清除（EXTERNAL 引用以轮换为准）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelCredentialUpdateCommand(
        String name,
        String secretRef,
        Integer priority,
        Integer weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        boolean enabled,
        Long version) {
}
