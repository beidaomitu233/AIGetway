package com.lightai.spi.secret;

import java.util.Optional;

/**
 * INLINE 秘密加密端口：主密钥不进数据库，密文含 nonce/tag 封装。
 * encryption_key_id 为主密钥标识（非密钥），随密文落库用于轮换。
 */
public interface SecretCipher {

    /** 加密明文；输出 = nonce || ciphertext || tag 封装字节。 */
    byte[] encrypt(char[] plaintext);

    /** 解密封装字节；封装格式不合法返回 empty。 */
    Optional<char[]> decrypt(byte[] sealed);

    /** 当前主密钥标识（落库 encryption_key_id）。 */
    String keyId();
}
