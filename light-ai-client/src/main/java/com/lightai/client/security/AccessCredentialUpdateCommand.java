package com.lightai.client.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 编辑 Access Credential：name/application/白名单/到期；enabled 走独立启停。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AccessCredentialUpdateCommand(
        String name,
        String application,
        List<String> allowedAliasIds,
        List<String> ipAllowlist,
        java.time.OffsetDateTime expiresAt,
        long version) {
}
