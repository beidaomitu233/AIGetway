package com.lightai.storage.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * INLINE 秘密加密验收（BE-013）：密文含 nonce/tag 封装、密钥不进数据库、
 * 篡改与密钥不匹配均不可解密；掩码不泄漏明文。
 */
class AesGcmSecretCipherTest {

    private static final String KEY_32 = Base64.getEncoder()
            .encodeToString(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                    17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});
    private static final String KEY_OTHER = Base64.getEncoder()
            .encodeToString(new byte[] {32, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17,
                    16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1});

    @Test
    void roundTripPreservesPlaintext() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(KEY_32, "key-2026-09");
        byte[] sealed = cipher.encrypt("sk-live-abcdef123456".toCharArray());
        assertThat(sealed).isNotEmpty();
        assertThat(cipher.keyId()).isEqualTo("key-2026-09");
        char[] plain = cipher.decrypt(sealed).orElseThrow();
        assertThat(new String(plain)).isEqualTo("sk-live-abcdef123456");
    }

    @Test
    void samePlaintextProducesDifferentCiphertext() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(KEY_32, "key-2026-09");
        byte[] first = cipher.encrypt("secret-value".toCharArray());
        byte[] second = cipher.encrypt("secret-value".toCharArray());
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void wrongKeyYieldsEmpty() {
        AesGcmSecretCipher encryptor = new AesGcmSecretCipher(KEY_32, "key-a");
        byte[] sealed = encryptor.encrypt("secret".toCharArray());
        AesGcmSecretCipher other = new AesGcmSecretCipher(KEY_OTHER, "key-a");
        assertThat(other.decrypt(sealed)).isEmpty();
    }

    @Test
    void tamperedCiphertextYieldsEmpty() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(KEY_32, "key-a");
        byte[] sealed = cipher.encrypt("secret".toCharArray());
        sealed[sealed.length - 1] ^= 0x01;
        assertThat(cipher.decrypt(sealed)).isEmpty();
    }
}
