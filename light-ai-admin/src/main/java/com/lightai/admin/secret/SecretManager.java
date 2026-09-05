package com.lightai.admin.secret;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 凭证秘密管理（BE-013）：
 * INLINE_ENCRYPTED 加密 secret_value；EXTERNAL_REF 加密完整引用；
 * masked_value 服务端生成且不可提交；明文仅存在于本次调用栈，用后清理。
 * 任何日志/审计/异常路径不得出现明文、掩码前值或完整引用。
 */
public final class SecretManager {

    public static final int SECRET_MIN = 1;
    public static final int SECRET_MAX = 4096;
    public static final int REF_MIN = 1;
    public static final int REF_MAX = 512;

    private final SecretCipher cipher;

    public SecretManager(SecretCipher cipher) {
        this.cipher = cipher;
    }

    /** 准备 INLINE 秘密：两次输入一致，返回待入库密文（调用方负责事务内持久化）。 */
    public Prepared prepareInline(String secretValue, String secretValueConfirm) {
        if (secretValue == null || secretValue.isBlank()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "secret_value 必填", "secret_value");
        }
        if (secretValueConfirm == null || !constantTimeEquals(secretValue, secretValueConfirm)) {
            throw new LightAiException(ErrorCode.SECRET_CONFIRM_MISMATCH, "两次输入的密钥不一致");
        }
        if (secretValue.length() < SECRET_MIN || secretValue.length() > SECRET_MAX) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "secret_value 长度 1—4096", "secret_value");
        }
        byte[] plain = secretValue.getBytes(StandardCharsets.UTF_8);
        try {
            SecretCipher.Sealed sealed = cipher.encrypt(plain);
            return new Prepared(sealed.ciphertext(), null, sealed.keyId(), mask(secretValue));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** 准备 EXTERNAL 引用：只保存加密后的完整引用，不解析、不回显。 */
    public Prepared prepareExternal(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "secret_ref 必填", "secret_ref");
        }
        if (secretRef.length() < REF_MIN || secretRef.length() > REF_MAX) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "secret_ref 长度 1—512", "secret_ref");
        }
        byte[] plain = secretRef.getBytes(StandardCharsets.UTF_8);
        try {
            SecretCipher.Sealed sealed = cipher.encrypt(plain);
            return new Prepared(null, sealed.ciphertext(), sealed.keyId(), maskRef(secretRef));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** 服务端安全掩码：保留首 3 与末 4 位，其余以 * 表示；过短值全部掩码。 */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "****";
        }
        int length = secret.length();
        if (length <= 7) {
            return "*".repeat(Math.max(4, length));
        }
        return secret.substring(0, 3) + "*".repeat(length - 7) + secret.substring(length - 4);
    }

    /** EXTERNAL 引用的非敏感标识：仅揭示引用方案前缀与末段。 */
    public static String maskRef(String secretRef) {
        String head = secretRef.contains("://") ? secretRef.substring(0, secretRef.indexOf("://") + 3) : "";
        return head + "****";
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }

    /** 待入库秘密：恰好一个密文非空。 */
    public record Prepared(byte[] secretCiphertext, byte[] secretRefCiphertext, String encryptionKeyId, String maskedValue) {

        public boolean inline() {
            return secretCiphertext != null;
        }
    }
}
