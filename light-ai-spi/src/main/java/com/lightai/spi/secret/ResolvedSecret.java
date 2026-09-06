package com.lightai.spi.secret;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * 已解析密钥封装（BE-053，4.6.3.6）：支持过期时间检查与显式内存擦除。
 */
public final class ResolvedSecret {

    private final char[] secret;
    private final Instant expiresAt;
    private volatile boolean cleared = false;

    public ResolvedSecret(char[] secret, Instant expiresAt) {
        this.secret = secret != null ? secret.clone() : new char[0];
        this.expiresAt = expiresAt;
    }

    public static ResolvedSecret of(char[] secret) {
        return new ResolvedSecret(secret, null);
    }

    public static ResolvedSecret of(char[] secret, Instant expiresAt) {
        return new ResolvedSecret(secret, expiresAt);
    }

    public char[] secret() {
        if (cleared) {
            throw new IllegalStateException("密钥已安全擦除");
        }
        return secret.clone();
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean isExpired(Instant now) {
        Instant checkTime = now != null ? now : Instant.now();
        return expiresAt != null && checkTime.isAfter(expiresAt);
    }

    public boolean isCleared() {
        return cleared;
    }

    /** 显式擦除密钥内存（全部置 0）。 */
    public synchronized void clear() {
        if (!cleared) {
            Arrays.fill(secret, '\0');
            cleared = true;
        }
    }

    @Override
    public String toString() {
        return "ResolvedSecret[masked=" + (cleared ? "CLEARED" : "****") + ", expiresAt=" + expiresAt + "]";
    }
}
