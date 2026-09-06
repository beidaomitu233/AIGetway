package com.lightai.provider.common;

import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;

/**
 * 统一错误分类基线（BACKEND_PLAN 4.7.2.5）：四个内置 Adapter 共用；
 * Adapter 可按 Provider 明确语义在子类/调用点细化，但不得把鉴权、权限、
 * 请求参数和正常内容过滤标记为普通可重试 5xx。
 */
public final class ErrorClassificationBaseline {

    private ErrorClassificationBaseline() {
    }

    public static ProviderErrorClassification classify(ProviderFailure failure, String unifiedCode) {
        return classify(failure, unifiedCode, false);
    }

    /**
     * @param modelMissing 按 Provider 明确语义判定的"模型不存在"细分
     */
    public static ProviderErrorClassification classify(ProviderFailure failure, String unifiedCode,
                                                       boolean modelMissing) {
        return switch (unifiedCode) {
            case "NETWORK_ERROR" -> new ProviderErrorClassification("NETWORK_ERROR", true, true, true, true);
            case "CONNECT_TIMEOUT" -> new ProviderErrorClassification("CONNECT_TIMEOUT", true, true, true, true);
            case "FIRST_TOKEN_TIMEOUT" -> new ProviderErrorClassification("FIRST_TOKEN_TIMEOUT", true, true, true, true);
            case "PROVIDER_AUTH_FAILED" -> new ProviderErrorClassification("PROVIDER_AUTH_FAILED", false, true, true, false);
            case "PROVIDER_RATE_LIMITED" -> new ProviderErrorClassification("PROVIDER_RATE_LIMITED", true, true, true, false);
            case "PROVIDER_MODEL_NOT_FOUND" -> new ProviderErrorClassification("PROVIDER_MODEL_NOT_FOUND", false, false, true, false);
            case "PROVIDER_REQUEST_REJECTED" -> new ProviderErrorClassification("PROVIDER_REQUEST_REJECTED", false, false, false, false);
            case "PROVIDER_BAD_RESPONSE" -> new ProviderErrorClassification("PROVIDER_BAD_RESPONSE", true, true, true, true);
            case "TOTAL_TIMEOUT" -> new ProviderErrorClassification("TOTAL_TIMEOUT", false, false, false, false);
            default -> new ProviderErrorClassification("PROVIDER_SERVER_ERROR", true, true, true, true);
        };
    }

    /** HTTP 状态到统一错误码的通用映射（4.7.2.5）；模型不存在由 Adapter 语义细分传入。 */
    public static String codeForStatus(int status) {
        return switch (status) {
            case 401, 403 -> "PROVIDER_AUTH_FAILED";
            case 404 -> "PROVIDER_MODEL_NOT_FOUND";
            case 429 -> "PROVIDER_RATE_LIMITED";
            case 400, 422 -> "PROVIDER_REQUEST_REJECTED";
            default -> "PROVIDER_SERVER_ERROR";
        };
    }
}
