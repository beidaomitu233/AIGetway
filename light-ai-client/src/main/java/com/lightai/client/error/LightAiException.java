package com.lightai.client.error;

import java.util.List;

/**
 * 承载统一错误码的业务异常：服务层抛出，HTTP 层映射为 UnifiedErrorEnvelope，
 * SDK 层映射为本地异常；禁止携带密钥、Token 或消息正文。
 */
public class LightAiException extends RuntimeException {

    private final ErrorCode code;
    private final String param;
    private final String requestId;
    private final Long retryAfterMs;
    private final Long currentVersion;
    private final Long currentStateVersion;
    private final List<FieldIssue> issues;

    public LightAiException(ErrorCode code, String message) {
        this(code, message, (String) null);
    }

    public LightAiException(ErrorCode code, String message, String param) {
        this(code, message, param, null, null, null, null, null);
    }

    public LightAiException(ErrorCode code, String message, List<FieldIssue> issues) {
        this(code, message, null, null, null, null, null, issues);
    }

    public LightAiException(ErrorCode code, String message, String param, String requestId,
                            Long retryAfterMs, Long currentVersion, Long currentStateVersion) {
        this(code, message, param, requestId, retryAfterMs, currentVersion, currentStateVersion, null);
    }

    public LightAiException(ErrorCode code, String message, String param, String requestId,
                            Long retryAfterMs, Long currentVersion, Long currentStateVersion,
                            List<FieldIssue> issues) {
        super(message);
        this.code = code;
        this.param = param;
        this.requestId = requestId;
        this.retryAfterMs = retryAfterMs;
        this.currentVersion = currentVersion;
        this.currentStateVersion = currentStateVersion;
        this.issues = issues == null ? null : List.copyOf(issues);
    }

    public List<FieldIssue> issues() {
        return issues;
    }

    public ErrorCode code() {
        return code;
    }

    public String param() {
        return param;
    }

    public String requestId() {
        return requestId;
    }

    public Long retryAfterMs() {
        return retryAfterMs;
    }

    public Long currentVersion() {
        return currentVersion;
    }

    public Long currentStateVersion() {
        return currentStateVersion;
    }

    public UnifiedError toError() {
        return UnifiedError.builder(code, getMessage())
                .param(param)
                .requestId(requestId)
                .retryAfterMs(retryAfterMs)
                .currentVersion(currentVersion)
                .currentStateVersion(currentStateVersion)
                .errors(issues)
                .build();
    }
}
