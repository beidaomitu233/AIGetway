package com.lightai.client.credential;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Credential 编辑命令（BACKEND_PLAN 4.2.9.2）。
 * secret_source 不可变；secret_ref 显式 null 不清除（EXTERNAL 引用以轮换为准）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialUpdateCommand(
        String name,
        String secretRef,
        Integer weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        boolean enabled,
        Long version) {
}
