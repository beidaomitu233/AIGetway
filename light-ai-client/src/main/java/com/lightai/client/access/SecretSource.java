package com.lightai.client.access;

/** 凭证秘密来源：创建后不可变（DATABASE_PLAN credential.secret_source）。 */
public enum SecretSource {
    INLINE_ENCRYPTED,
    EXTERNAL_REF
}
