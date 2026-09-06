package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 轮换命令：generation+1，旧 Token 立即拒绝，enabled 不变，新 Token 只本次返回。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccessCredentialRotateCommand(long version, String reason) {
}
