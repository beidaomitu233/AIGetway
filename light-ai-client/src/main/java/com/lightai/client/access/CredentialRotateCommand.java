package com.lightai.client.access;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 轮换密钥命令（4.2.9.2）：独立事务、即时生效，旧密钥立即失效；
 * 不进入草稿差异与配置快照，仅递增 secret_version 与实体 version。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CredentialRotateCommand(
        String secretValue,
        String secretValueConfirm,
        long version) {
}
