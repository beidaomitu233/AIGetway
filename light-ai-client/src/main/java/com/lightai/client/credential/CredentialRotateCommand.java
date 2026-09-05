package com.lightai.client.credential;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Credential 轮换命令（BACKEND_PLAN 4.2.9.2）。
 * 两次输入不一致返回 SECRET_CONFIRM_MISMATCH；轮换即时生效并递增 secret_version，
 * 旧值立即不可用且任何响应不回读。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialRotateCommand(
        String secretValue,
        String secretValueConfirm,
        Long version) {
}
