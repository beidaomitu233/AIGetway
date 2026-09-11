package com.lightai.storage.channel;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * channel_credential 表行（DATABASE_PLAN §3，存储类别 C，最高敏感）。
 * V2：渠道 Key 直挂渠道，原 credential_secret 保护列折叠进本表；
 * secret_source 由密文列推导（密文引用非空为 EXTERNAL_REF，否则 INLINE_ENCRYPTED），
 * 创建后不可切换。列本身不含明文，明文仅以 AES-GCM 密文落库。
 */
public record ChannelCredentialRecord(
        UUID id,
        UUID channelId,
        String name,
        byte[] secretCiphertext,
        byte[] secretRefCiphertext,
        String keyId,
        String maskedValue,
        long secretVersion,
        OffsetDateTime rotatedAt,
        int priority,
        int weight,
        Long rpmLimit,
        Long tpmLimit,
        Integer concurrentLimit,
        String status,
        String health,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String HEALTH_UNKNOWN = "UNKNOWN";
    public static final String SOURCE_INLINE = "INLINE_ENCRYPTED";
    public static final String SOURCE_EXTERNAL = "EXTERNAL_REF";

    /** 兼容读取：status == ACTIVE 视为启用。 */
    public boolean enabled() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    /** 秘密来源由密文列推导。 */
    public String secretSource() {
        return secretRefCiphertext != null ? SOURCE_EXTERNAL : SOURCE_INLINE;
    }
}
