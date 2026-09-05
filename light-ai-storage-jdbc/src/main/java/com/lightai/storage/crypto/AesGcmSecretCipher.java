package com.lightai.storage.crypto;

import com.lightai.spi.secret.SecretCipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 秘密加密实现（DATABASE_PLAN credential_secret.secret_ciphertext）。
 * 封装格式：版本字节(1) + keyId长度与字节 + nonce(12) + ciphertext+tag。
 * 主密钥来自部署配置（Base64 的 32 字节），不进数据库、不进日志。
 */
public final class AesGcmSecretCipher implements SecretCipher {

    private static final byte VERSION = 1;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey masterKey;
    private final String keyId;

    public AesGcmSecretCipher(String base64MasterKey, String keyId) {
        byte[] keyBytes = Base64.getDecoder().decode(base64MasterKey);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("主密钥必须为 32 字节（Base64）");
        }
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
        this.keyId = keyId;
    }

    @Override
    public byte[] encrypt(char[] plaintext) {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plain = new String(plaintext).getBytes(StandardCharsets.UTF_8);
            byte[] sealed = cipher.doFinal(plain);
            java.util.Arrays.fill(plain, (byte) 0);

            byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + keyIdBytes.length
                    + NONCE_LENGTH + sealed.length);
            buffer.put(VERSION);
            buffer.put((byte) keyIdBytes.length);
            buffer.put(keyIdBytes);
            buffer.put(nonce);
            buffer.put(sealed);
            return buffer.array();
        } catch (Exception e) {
            throw new IllegalStateException("秘密加密失败", e);
        } finally {
            java.util.Arrays.fill(nonce, (byte) 0);
        }
    }

    @Override
    public Optional<char[]> decrypt(byte[] sealedBytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(sealedBytes);
            byte version = buffer.get();
            if (version != VERSION) {
                return Optional.empty();
            }
            int keyIdLength = buffer.get();
            byte[] keyIdBytes = new byte[keyIdLength];
            buffer.get(keyIdBytes);
            if (!keyId.equals(new String(keyIdBytes, StandardCharsets.UTF_8))) {
                // 主密钥已轮换：由部署侧解密迁移，不在运行时猜测
                return Optional.empty();
            }
            byte[] nonce = new byte[NONCE_LENGTH];
            buffer.get(nonce);
            byte[] sealed = new byte[buffer.remaining()];
            buffer.get(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plain = cipher.doFinal(sealed);
            char[] chars = new String(plain, StandardCharsets.UTF_8).toCharArray();
            java.util.Arrays.fill(plain, (byte) 0);
            return Optional.of(chars);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public String keyId() {
        return keyId;
    }
}
