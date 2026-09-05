package com.lightai.storage.credential;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * credential_secret 受保护行（DATABASE_PLAN §4）。
 * 明文与完整引用以密文落库（bytea），任何响应只使用 maskedValue；
 * secret_version 每次轮换递增，用于进程内句柄缓存失效。
 */
public record SecretRecordRow(
        UUID id,
        UUID credentialId,
        byte[] secretCiphertext,
        byte[] secretRefCiphertext,
        String encryptionKeyId,
        String maskedValue,
        long secretVersion,
        OffsetDateTime rotatedAt) {

    public SecretRecordRow {
        // 防御性复制，密文字节不共享引用
        secretCiphertext = secretCiphertext == null ? null : secretCiphertext.clone();
        secretRefCiphertext = secretRefCiphertext == null ? null : secretRefCiphertext.clone();
    }
}
