package com.lightai.client.error;

/**
 * 统一错误码（BACKEND_PLAN 4.7.3）。HTTP 状态与 retryable 以该表为唯一来源；
 * HTTP 状态为 -1 的错误只在 Java SDK 本地产生，不伪造 HTTP 状态。
 */
public enum ErrorCode {

    FIELD_VALIDATION_FAILED(400, false, ErrorType.INVALID_REQUEST),
    SECRET_CONFIRM_MISMATCH(400, false, ErrorType.INVALID_REQUEST),
    CONFIRMATION_TEXT_MISMATCH(400, false, ErrorType.INVALID_REQUEST),
    REQUEST_TOO_LARGE(413, false, ErrorType.INVALID_REQUEST),
    UNSUPPORTED_CONTENT_TYPE(415, false, ErrorType.INVALID_REQUEST),
    UNSUPPORTED_CONTENT_ENCODING(415, false, ErrorType.INVALID_REQUEST),

    ACCESS_TOKEN_INVALID(401, false, ErrorType.AUTHENTICATION),
    INSTANCE_AUTH_FAILED(401, false, ErrorType.AUTHENTICATION),

    ACCESS_IP_DENIED(403, false, ErrorType.PERMISSION),
    ACCESS_DENIED(403, false, ErrorType.PERMISSION),

    OBJECT_NOT_FOUND(404, false, ErrorType.NOT_FOUND),
    MODEL_ALIAS_NOT_FOUND(404, false, ErrorType.NOT_FOUND),

    OBJECT_IN_USE(409, false, ErrorType.CONFLICT),
    CAPACITY_IN_USE(409, true, ErrorType.CONFLICT),
    IMPACT_ANALYSIS_EXPIRED(409, false, ErrorType.CONFLICT),
    DUPLICATE_ROUTE_CANDIDATE(409, false, ErrorType.CONFLICT),
    JOB_ALREADY_FINISHED(409, false, ErrorType.CONFLICT),
    MODEL_ALIAS_DISABLED(409, false, ErrorType.CONFLICT),
    LIMIT_POLICY_CONFLICT(409, false, ErrorType.CONFLICT),
    RELIABILITY_POLICY_CONFLICT(409, false, ErrorType.CONFLICT),
    CIRCUIT_STATE_CONFLICT(409, true, ErrorType.CONFLICT),
    TRACE_ID_CONFLICT(409, false, ErrorType.CONFLICT),
    ADMIN_PATH_CONFLICT(409, false, ErrorType.CONFLICT),
    SECRET_PROVIDER_CONFLICT(409, false, ErrorType.CONFLICT),
    CONFIG_DRAFT_CHANGED(409, false, ErrorType.CONFLICT),
    DRAFT_REVERT_BLOCKED(409, false, ErrorType.CONFLICT),
    CONFIG_VALIDATION_EXPIRED(409, false, ErrorType.CONFLICT),
    CONFIG_PUBLISH_IN_PROGRESS(409, true, ErrorType.CONFLICT),
    CONFIG_FIELD_IMMUTABLE(409, false, ErrorType.CONFLICT),
    RETENTION_IMPACT_EXPIRED(409, false, ErrorType.CONFLICT),
    ACCESS_CREDENTIAL_EXPIRED(409, false, ErrorType.CONFLICT),
    SNAPSHOT_CHECKSUM_MISMATCH(409, true, ErrorType.CONFLICT),
    INSTANCE_REPORT_CONFLICT(409, true, ErrorType.CONFLICT),

    PROVIDER_ADAPTER_NOT_FOUND(422, false, ErrorType.INVALID_REQUEST),
    OBJECT_REFERENCE_INVALID(422, false, ErrorType.INVALID_REQUEST),
    CHECK_TARGET_INVALID(422, false, ErrorType.INVALID_REQUEST),
    MODEL_LIST_NOT_SUPPORTED(422, false, ErrorType.INVALID_REQUEST),
    MODEL_CAPABILITY_NOT_SUPPORTED(422, false, ErrorType.INVALID_REQUEST),
    CONTEXT_WINDOW_EXCEEDED(422, false, ErrorType.INVALID_REQUEST),
    EXPORT_TOO_LARGE(422, false, ErrorType.INVALID_REQUEST),
    MODE_NOT_SUPPORTED(422, false, ErrorType.INVALID_REQUEST),
    INSTANCE_VERSION_INCOMPATIBLE(422, false, ErrorType.INVALID_REQUEST),

    CAPACITY_LIMITED(429, true, ErrorType.RATE_LIMIT),
    QUEUE_FULL(429, true, ErrorType.RATE_LIMIT),
    QUEUE_TIMEOUT(429, true, ErrorType.RATE_LIMIT),

    PROVIDER_AUTH_FAILED(502, false, ErrorType.API),
    PROVIDER_MODEL_NOT_FOUND(502, false, ErrorType.API),
    PROVIDER_REQUEST_REJECTED(502, false, ErrorType.API),
    PROVIDER_RATE_LIMITED(503, true, ErrorType.RATE_LIMIT),
    PROVIDER_BAD_RESPONSE(502, true, ErrorType.API),
    SERVER_PROTOCOL_ERROR(-1, false, ErrorType.API),
    PROVIDER_SERVER_ERROR(503, true, ErrorType.API),
    SECRET_RESOLUTION_FAILED(502, true, ErrorType.API),
    IMPORT_SOURCE_UNAVAILABLE(503, true, ErrorType.API),
    NETWORK_ERROR(503, true, ErrorType.API),
    CREDENTIAL_NOT_AVAILABLE(503, true, ErrorType.API),
    CIRCUIT_OPEN(503, true, ErrorType.API),
    CAPACITY_STATE_UNAVAILABLE(503, true, ErrorType.API),
    OBSERVATION_DATA_UNAVAILABLE(503, true, ErrorType.API),
    CONFIG_DATA_UNAVAILABLE(503, true, ErrorType.API),
    AUDIT_DATA_UNAVAILABLE(503, true, ErrorType.API),
    NO_ONLINE_RUNTIME_INSTANCE(503, true, ErrorType.API),
    CONNECT_TIMEOUT(504, true, ErrorType.TIMEOUT),
    FIRST_TOKEN_TIMEOUT(504, true, ErrorType.TIMEOUT),
    TOTAL_TIMEOUT(504, true, ErrorType.TIMEOUT),
    ALL_CANDIDATES_FAILED(503, true, ErrorType.API),
    STREAM_INTERRUPTED(502, true, ErrorType.API),
    CLIENT_CANCELLED(499, false, ErrorType.CANCELLED),
    CLIENT_CLOSED(-1, false, ErrorType.CANCELLED),

    CONFIG_VERSION_CONFLICT(409, false, ErrorType.CONFLICT),
    INTERNAL_ERROR(500, false, ErrorType.API);

    private final int httpStatus;
    private final boolean retryable;
    private final ErrorType type;

    ErrorCode(int httpStatus, boolean retryable, ErrorType type) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.type = type;
    }

    /** -1 表示仅 SDK 本地错误，不出现在 HTTP 响应中。 */
    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    public ErrorType type() {
        return type;
    }
}
