package com.lightai.client.error;

/** HTTP 同步错误统一包装结构：{"error": UnifiedError}。 */
public record UnifiedErrorEnvelope(UnifiedError error) {

    public static UnifiedErrorEnvelope of(UnifiedError error) {
        return new UnifiedErrorEnvelope(error);
    }
}
