package com.lightai.spi.secret;

import java.time.Instant;
import java.util.Objects;

/**
 * 密钥解析请求（BE-053，4.6.3.6）：支持解析超时 deadline。
 */
public record SecretResolveRequest(
        String secretRef,
        Instant deadline) {

    public SecretResolveRequest {
        Objects.requireNonNull(secretRef, "secretRef 不能为空");
    }

    public static SecretResolveRequest of(String secretRef) {
        return new SecretResolveRequest(secretRef, null);
    }

    public static SecretResolveRequest of(String secretRef, Instant deadline) {
        return new SecretResolveRequest(secretRef, deadline);
    }
}
