package com.lightai.spi.secret;

import java.util.Optional;

/**
 * 外部密钥供给 SPI（BE-P05 提供实现）：按 secret_ref 解析外部 Secret。
 * 多个实现同时匹配同一引用即 SECRET_PROVIDER_CONFLICT；
 * 实现不得记录 secret_ref 明文与解析结果。
 */
public interface SecretProvider {

    /** 是否支持解析该引用；引用格式非法返回 false。 */
    boolean supports(String secretRef);

    /** 解析密钥原文；失败返回 empty（由调用方映射 SECRET_RESOLUTION_FAILED）。 */
    Optional<char[]> resolve(String secretRef);

    /** 引用掩码展示（如 vault://…前 12 位），不携带可定位的完整路径。 */
    default String mask(String secretRef) {
        return secretRef == null ? "****" : (secretRef.substring(0, Math.min(12, secretRef.length())) + "…");
    }
}
