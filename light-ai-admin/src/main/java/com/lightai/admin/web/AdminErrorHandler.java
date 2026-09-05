package com.lightai.admin.web;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.UnifiedError;
import com.lightai.client.error.UnifiedErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 管理接口统一错误映射：LightAiException 按错误码表映射 HTTP 与 UnifiedErrorEnvelope，
 * 未分类异常一律 INTERNAL_ERROR 且不回传内部细节。
 * 日志只记录 request_id、错误码与耗时，不含请求体、认证头与消息正文。
 */
@RestControllerAdvice(basePackages = "com.lightai.admin")
public class AdminErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminErrorHandler.class);

    @ExceptionHandler(com.lightai.client.error.LightAiException.class)
    public ResponseEntity<String> handleLightAiException(com.lightai.client.error.LightAiException e,
                                                         HttpServletRequest request) {
        UnifiedError error = e.toError();
        if (error.requestId() == null) {
            error = UnifiedError.builder(e.code(), e.getMessage())
                    .param(e.param())
                    .errors(e.issues())
                    .requestId(RequestIdFilter.requestIdOf(request))
                    .retryAfterMs(e.retryAfterMs())
                    .currentVersion(e.currentVersion())
                    .currentStateVersion(e.currentStateVersion())
                    .build();
        }
        log.info("管理请求失败 request_id={} code={} 耗时ms={}",
                error.requestId(), error.code(), elapsed(request));
        return ResponseEntity.status(httpStatus(e.code()))
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.error(error));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(Exception e, HttpServletRequest request) {
        String requestId = RequestIdFilter.requestIdOf(request);
        // 未分类错误不向客户端回传 e.getMessage()，避免泄漏内部细节
        UnifiedError error = UnifiedError.builder(ErrorCode.INTERNAL_ERROR, "内部错误，请提供 request_id 联系管理员")
                .requestId(requestId)
                .build();
        log.error("管理请求未分类异常 request_id={} 耗时ms={} exception={}",
                requestId, elapsed(request), e.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.error(error));
    }

    static int httpStatus(ErrorCode code) {
        int status = code.httpStatus();
        return status <= 0 ? ErrorCode.INTERNAL_ERROR.httpStatus() : status;
    }

    private static long elapsed(HttpServletRequest request) {
        Object start = request.getAttribute(AdminAuthInterceptor.RequestStart.ATTRIBUTE);
        if (start instanceof Long begin) {
            return Duration.ofNanos(System.nanoTime() - begin).toMillis();
        }
        return -1;
    }
}
