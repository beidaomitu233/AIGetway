package com.lightai.client.error;

/**
 * UnifiedError.type 的稳定枚举（C-015 补充假设，随 OpenAPI 冻结确认）。
 */
public enum ErrorType {
    INVALID_REQUEST("invalid_request_error"),
    AUTHENTICATION("authentication_error"),
    PERMISSION("permission_error"),
    NOT_FOUND("not_found_error"),
    CONFLICT("conflict_error"),
    RATE_LIMIT("rate_limit_error"),
    TIMEOUT("timeout_error"),
    CANCELLED("cancelled_error"),
    API("api_error");

    private final String wireValue;

    ErrorType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
