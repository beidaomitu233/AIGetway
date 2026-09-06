package com.lightai.admin.security;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.security.SecureRandom;
import java.util.function.Supplier;

/**
 * 业务 Token 签发与摘要（BE-044）：32 字节随机 + lai_ 前缀；
 * 持久化 HMAC-SHA256 摘要（pepper 版本化，pepper 不进数据库）；
 * token_value 只在签发/轮换响应中出现一次；掩码不含可反推信息。
 */
public final class AccessTokenService {

    public static final String TOKEN_PREFIX = "lai_";
    private static final int TOKEN_BYTES = 32;

    /** 外部 pepper 提供方：版本 + pepper 字节（部署注入，不入库）。 */
    public interface PepperProvider {
        Pepper current();

        record Pepper(int version, byte[] bytes) {
        }
    }

    /** 静态 pepper（配置注入用）。 */
    public static PepperProvider fixedPepper(int version, String pepper) {
        byte[] bytes = pepper.getBytes(StandardCharsets.UTF_8);
        return () -> new PepperProvider.Pepper(version, bytes);
    }

    private final PepperProvider pepperProvider;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public AccessTokenService(PepperProvider pepperProvider) {
        this.pepperProvider = pepperProvider;
    }

    /** 签发新 Token：lai_ + 43 字符 base64url。 */
    public Issued issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        try {
            String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            PepperProvider.Pepper current = pepperProvider.current();
            return new Issued(token, digest(token, current), current.version(), mask(token), prefix(token));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** 摘要：HMAC-SHA256(pepper, token)，32 字节。 */
    public byte[] digest(String token, int expectedVersion) {
        PepperProvider.Pepper pepper = pepperProvider.current();
        if (pepper.version() != expectedVersion) {
            throw new LightAiException(ErrorCode.INTERNAL_ERROR, "Token pepper 版本不一致");
        }
        return digest(token, pepper);
    }

    public byte[] digest(String token) {
        return digest(token, pepperProvider.current());
    }

    public static byte[] digest(String token, PepperProvider.Pepper pepper) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(pepper.bytes(), "HmacSHA256"));
            return mac.doFinal(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Token 摘要失败", e);
        }
    }

    public static String prefix(String token) {
        return token.substring(0, Math.min(8, token.length()));
    }

    /** 安全掩码：lai_**** 后 4 位。 */
    public static String mask(String token) {
        if (token == null || token.length() < 8) {
            return TOKEN_PREFIX + "****";
        }
        return TOKEN_PREFIX + "****" + token.substring(token.length() - 4);
    }

    public record Issued(String tokenValue, byte[] tokenHash, int pepperVersion, String maskedValue, String prefix) {
    }
}
