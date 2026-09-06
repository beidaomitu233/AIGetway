package com.lightai.spi.secret;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 外部密钥供给 SPI（BE-053，4.6.3.6）：按 secret_ref 解析外部 Secret。
 * 多个实现同时匹配同一引用即 SECRET_PROVIDER_CONFLICT；
 * 实现不得记录 secret_ref 明文与解析结果。
 */
public interface SecretProvider {

    /** 是否支持解析该引用；引用格式非法返回 false。 */
    boolean supports(String secretRef);

    /** 解析密钥原文；失败返回 empty（由调用方映射 SECRET_RESOLUTION_FAILED）。 */
    Optional<char[]> resolve(String secretRef);

    /**
     * 带 deadline 的异步解析方法（BE-053）。
     * 默认实现委托给同步 resolve 方法。
     */
    default CompletionStage<ResolvedSecret> resolve(SecretResolveRequest request) {
        CompletableFuture<ResolvedSecret> future = new CompletableFuture<>();
        try {
            Optional<char[]> res = resolve(request.secretRef());
            if (res.isPresent()) {
                future.complete(ResolvedSecret.of(res.get()));
            } else {
                future.completeExceptionally(new IllegalArgumentException("无法解析密钥引用: " + mask(request.secretRef())));
            }
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    /**
     * Credential 轮换或主动失效时调用（BE-053）；重复调用幂等。
     *
     * @param secretRef 密钥引用
     * @param version   版本号
     */
    default void invalidate(String secretRef, int version) {
        // 默认空实现，由有缓存的 SPI 实现覆盖
    }

    /** 引用掩码展示（如 vault://…前 12 位），不携带可定位的完整路径。 */
    default String mask(String secretRef) {
        return secretRef == null ? "****" : (secretRef.substring(0, Math.min(12, secretRef.length())) + "…");
    }
}
