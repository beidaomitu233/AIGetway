package com.lightai.client.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 统一错误对象：code、type、message、retryable 必有；
 * 其余字段按错误条件输出（BACKEND_PLAN 2.3/4.7.3）。
 * message 不得包含 Credential、Authorization、消息正文和 Provider 原始敏感信息。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnifiedError(
        String code,
        String type,
        String message,
        boolean retryable,
        String param,
        List<FieldIssue> errors,
        String traceId,
        Long retryAfterMs,
        String requestId,
        Long currentVersion,
        Long currentStateVersion) {

    public static UnifiedError of(ErrorCode code, String message) {
        return new UnifiedError(code.name(), code.type().wireValue(), message, code.retryable(),
                null, null, null, null, null, null, null);
    }

    public static Builder builder(ErrorCode code, String message) {
        return new Builder(code, message);
    }

    public static final class Builder {
        private final ErrorCode code;
        private final String message;
        private String param;
        private List<FieldIssue> errors;
        private String traceId;
        private Long retryAfterMs;
        private String requestId;
        private Long currentVersion;
        private Long currentStateVersion;

        private Builder(ErrorCode code, String message) {
            this.code = code;
            this.message = message;
        }

        public Builder param(String param) {
            this.param = param;
            return this;
        }

        public Builder errors(List<FieldIssue> errors) {
            this.errors = errors;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder retryAfterMs(Long retryAfterMs) {
            this.retryAfterMs = retryAfterMs;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder currentVersion(Long currentVersion) {
            this.currentVersion = currentVersion;
            return this;
        }

        public Builder currentStateVersion(Long currentStateVersion) {
            this.currentStateVersion = currentStateVersion;
            return this;
        }

        public UnifiedError build() {
            return new UnifiedError(code.name(), code.type().wireValue(), message, code.retryable(),
                    param, errors, traceId, retryAfterMs, requestId, currentVersion, currentStateVersion);
        }
    }
}
