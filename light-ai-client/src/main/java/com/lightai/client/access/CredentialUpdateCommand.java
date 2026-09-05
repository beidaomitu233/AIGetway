package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 编辑 Credential 命令：不接受 secret_value，密钥变更必须使用轮换；
 * EXTERNAL_REF 可更新引用。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CredentialUpdateCommand(
        String name,
        String secretRef,
        Integer weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        Boolean enabled,
        long version) {
}
