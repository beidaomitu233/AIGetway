package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 新建 Access Credential（协议字典）：token 只在本次 SecretResult 返回一次。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccessCredentialCreateCommand(
        String name,
        String application,
        List<String> allowedAliasIds,
        List<String> ipAllowlist,
        java.time.OffsetDateTime expiresAt,
        Boolean enabled) {
}
