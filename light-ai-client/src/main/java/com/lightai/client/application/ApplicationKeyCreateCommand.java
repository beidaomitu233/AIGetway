package com.lightai.client.application;

import java.time.OffsetDateTime;
import java.util.List;

/** 签发应用密钥；可选限制必须比应用策略更严格。 */
public record ApplicationKeyCreateCommand(
        String name,
        List<String> ipAllowlist,
        OffsetDateTime expiresAt,
        Integer rpm,
        Long tpm,
        List<String> virtualModelIds) {

    public ApplicationKeyCreateCommand {
        ipAllowlist = ipAllowlist == null ? List.of() : List.copyOf(ipAllowlist);
        virtualModelIds = virtualModelIds == null ? List.of() : List.copyOf(virtualModelIds);
    }

    public ApplicationKeyCreateCommand(
            String name, List<String> ipAllowlist, OffsetDateTime expiresAt,
            Integer rpm, Long tpm) {
        this(name, ipAllowlist, expiresAt, rpm, tpm, List.of());
    }
}
