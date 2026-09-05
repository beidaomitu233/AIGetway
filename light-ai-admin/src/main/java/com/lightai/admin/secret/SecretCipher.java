package com.lightai.admin.secret;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * Secret 加密器（BE-013）：AES-256-GCM，密文封装 nonce+cipher+tag。
 * 主密钥由部署提供（SecretKeyProvider），绝不进入数据库、快照或日志；
 * keyId 仅为主密钥标识（非密钥本身），随密文入库支持密钥轮换。
 */
public final class SecretCipher {

    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;

    /** 部署侧主密钥来源：keyId + 32 字节密钥。 */
    public interface SecretKeyProvider {
        Key current();

        record Key(String keyId, byte[] keyBytes) {
        }
    }

    /** 从 Base64 配置构造的静态密钥提供方（Server/Starter 装配用）。 */
    public static SecretKeyProvider fixedKeyProvider(String keyId, String base64Key) {
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("主密钥必须为 32 字节（Base64 编码）");
        }
        return () -> new SecretKeyProvider.Key(keyId, key);
    }

    public record Sealed(byte[] ciphertext, String keyId) {
    }

    private final SecretKeyProvider keyProvider;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(SecretKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    public Sealed encrypt(byte[] plaintext) {
        SecretKeyProvider.Key key = keyProvider.current();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key.keyBytes(), "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] sealed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, sealed, 0, nonce.length);
            System.arraycopy(encrypted, 0, sealed, nonce.length, encrypted.length);
            return new Sealed(sealed, key.keyId());
        } catch (Exception e) {
            throw new IllegalStateException("Secret 加密失败：" + e.getClass().getSimpleName(), e);
        } finally {
            java.util.Arrays.fill(nonce, (byte) 0);
        }
    }

    /** 解密：运行期 Secret 解析专用；失败统一为确定异常，不输出密文内容。 */
    public byte[] decrypt(byte[] sealed) {
        SecretKeyProvider.Key key = keyProvider.current();
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key.keyBytes(), "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, sealed, 0, NONCE_BYTES));
            return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("Secret 解密失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 供测试/示例生成密钥。 */
    public static Supplier<String> randomBase64Key() {
        return () -> {
            byte[] key = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(key);
            return Base64.getEncoder().encodeToString(key);
        };
    }
}
