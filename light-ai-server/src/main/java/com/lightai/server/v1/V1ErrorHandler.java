package com.lightai.server.v1;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * /v1 统一错误映射（BE-027）：LightAiException → 对应 HTTP 状态与
 * UnifiedErrorEnvelope；错误响应禁缓存；未分类异常映射 INTERNAL_ERROR。
 */
@RestControllerAdvice
public class V1ErrorHandler {

    @ExceptionHandler(LightAiException.class)
    public ResponseEntity<String> handle(LightAiException e) {
        int status = e.code().httpStatus();
        return ResponseEntity.status(status > 0 ? status : 500)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Cache-Control", "no-store")
                .header(V1Controller.VERSION_HEADER, V1Controller.SERVER_VERSION)
                .body(V1Controller.errorBody(e));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnknown(Exception e) {
        LightAiException internal = new LightAiException(ErrorCode.INTERNAL_ERROR, "未分类内部错误");
        return ResponseEntity.status(500)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Cache-Control", "no-store")
                .header(V1Controller.VERSION_HEADER, V1Controller.SERVER_VERSION)
                .body(V1Controller.errorBody(internal));
    }
}
