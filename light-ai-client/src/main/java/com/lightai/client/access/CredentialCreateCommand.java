package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 新建 Credential 命令（4.2.9.2）。
 * secret_value 与 secret_ref 互斥；INLINE_ENCRYPTED 要求二次确认一致；
 * 密钥明文仅本次请求可见，响应与审计永不返回。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CredentialCreateCommand(
        String name,
        String secretSource,
        String secretValue,
        String secretValueConfirm,
        String secretRef,
        Integer weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        Boolean enabled) {
}
