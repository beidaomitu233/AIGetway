package com.lightai.client.credential;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Credential 创建命令（BACKEND_PLAN 4.2.9.2）。
 * secret_source 创建后不可切换：INLINE 必填 secret_value，EXTERNAL 必填 secret_ref；
 * 明文仅进入受保护存储，不进入草稿、审计与日志。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialCreateCommand(
        String name,
        String secretSource,
        String secretValue,
        String secretRef,
        Integer weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        boolean enabled) {

    public static final int WEIGHT_MIN = 1;
    public static final int WEIGHT_MAX = 100;
    public static final int CONCURRENT_LIMIT_MAX = 100000;

    public CredentialCreateCommand {
        if (name == null || name.strip().length() < 2 || name.strip().length() > 64) {
            throw new IllegalArgumentException("name 长度必须为 2—64");
        }
        if (!CredentialListItem.SOURCE_INLINE.equals(secretSource)
                && !CredentialListItem.SOURCE_EXTERNAL.equals(secretSource)) {
            throw new IllegalArgumentException("secret_source 仅支持 INLINE_ENCRYPTED/EXTERNAL_REF");
        }
        if (CredentialListItem.SOURCE_INLINE.equals(secretSource)
                && (secretValue == null || secretValue.isBlank())) {
            throw new IllegalArgumentException("INLINE_ENCRYPTED 必须提供 secret_value");
        }
        if (CredentialListItem.SOURCE_EXTERNAL.equals(secretSource)
                && (secretRef == null || secretRef.isBlank())) {
            throw new IllegalArgumentException("EXTERNAL_REF 必须提供 secret_ref");
        }
        if (weight == null || weight < WEIGHT_MIN || weight > WEIGHT_MAX) {
            throw new IllegalArgumentException("weight 范围 1—100");
        }
        if ((rpmLimit != null && rpmLimit <= 0) || (tpmLimit != null && tpmLimit <= 0)) {
            throw new IllegalArgumentException("限额为空表示不限，0 不合法");
        }
        if (concurrentLimit != null
                && (concurrentLimit < 1 || concurrentLimit > CONCURRENT_LIMIT_MAX)) {
            throw new IllegalArgumentException("concurrent_limit 范围 1—" + CONCURRENT_LIMIT_MAX);
        }
    }

    public String name() {
        return name == null ? null : name.strip();
    }
}
