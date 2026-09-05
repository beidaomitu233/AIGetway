package com.lightai.client.bootstrap;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Bootstrap 身份信息：只含非敏感展示字段，不含密钥与 Token。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BootstrapUser(
        String id,
        String displayName) {

    public BootstrapUser {
        id = id == null ? "" : id;
        displayName = displayName == null ? "" : displayName;
    }
}
