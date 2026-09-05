package com.lightai.storage.credential;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * credential_secret 受保护行（DATABASE_PLAN §4，R类）。
 * ciphertext 与 ref_ciphertext 互斥；库账户层面禁止普通查询服务读取密文列。
 */
public record SecretRecord(
        UUID credentialId,
        byte[] secretCiphertext,
        byte[] secretRefCiphertext,
        String encryptionKeyId,
        String maskedValue,
        long secretVersion,
        OffsetDateTime rotatedAt,
        OffsetDateTime updatedAt) {

    public boolean inline() {
        return secretCiphertext != null;
    }
}
