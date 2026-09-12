package com.lightai.server.v1;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.error.UnifiedError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * /v1 统一错误映射（BE-027/BE-221）：LightAiException → 对应 HTTP 状态与
 * UnifiedErrorEnvelope；错误体注入请求关联 request_id，429 携带 Retry-After 响应头；
 * 错误响应禁缓存；未分类异常映射 INTERNAL_ERROR。
 * 作用域限定在 /v1 控制器包，避免覆盖 /admin 的错误处理与日志。
 */
@RestControllerAdvice(basePackages = "com.lightai.server.v1")
public class V1ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(V1ErrorHandler.class);

    @ExceptionHandler(LightAiException.class)
    public ResponseEntity<String> handle(LightAiException e, HttpServletRequest request) {
        if (e.code() == ErrorCode.INTERNAL_ERROR) {
            log.error("/v1 请求未分类失败 code=INTERNAL_ERROR", e);
        }
        UnifiedError error = withRequestId(e.toError(), request);
        return respond(e.code().httpStatus(), error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnknown(Exception e, HttpServletRequest request) {
        // 服务端记录完整堆栈供诊断；响应体不回传内部细节
        log.error("/v1 请求未分类异常 exception={}", e.getClass().getSimpleName(), e);
        LightAiException internal = new LightAiException(ErrorCode.INTERNAL_ERROR, "未分类内部错误");
        return respond(500, withRequestId(internal.toError(), request));
    }

    private ResponseEntity<String> respond(int status, UnifiedError error) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status > 0 ? status : 500)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Cache-Control", "no-store")
                .header(V1Controller.VERSION_HEADER, V1Controller.SERVER_VERSION);
        if (error.retryAfterMs() != null && error.retryAfterMs() > 0) {
            builder.header("Retry-After", String.valueOf(
                    Math.max(1, Math.round(error.retryAfterMs() / 1000.0))));
        }
        return builder.body(V1Controller.errorJson(error));
    }

    /** request_id 来自 RequestIdFilter（调用方合法头回传或服务端生成）。 */
    private static UnifiedError withRequestId(UnifiedError error, HttpServletRequest request) {
        if (error.requestId() != null || request == null) {
            return error;
        }
        String requestId = com.lightai.admin.web.RequestIdFilter.requestIdOf(request);
        if (requestId == null) {
            return error;
        }
        return new UnifiedError(error.code(), error.type(), error.message(), error.retryable(),
                error.param(), error.errors(), error.traceId(), error.retryAfterMs(),
                requestId, error.currentVersion(), error.currentStateVersion());
    }
}
