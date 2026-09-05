package com.lightai.admin.web;

import com.lightai.admin.AdminProperties;
import com.lightai.admin.draft.WriteContext;

/**
 * Controller 公共工具：请求体严格反序列化（未知字段拒绝）、
 * 写上下文提取、响应封装。
 */
public final class Controllers {

    private Controllers() {
    }

    public static RequestContext context(org.springframework.web.context.request.WebRequest request) {
        return (RequestContext) request.getAttribute(RequestContext.ATTRIBUTE, org.springframework.web.context.request.WebRequest.SCOPE_REQUEST);
    }

    public static WriteContext writeContext(RequestContext context, AdminProperties properties) {
        return new WriteContext(
                context.requestId(),
                context.authContext().userId(),
                properties.getRuntimeMode(),
                context.sourceIpMasked());
    }

    public static <T> T parseBody(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            try {
                return com.lightai.client.json.ProtocolJson.strictCommands().readValue("{}", type);
            } catch (Exception e) {
                throw new com.lightai.client.error.LightAiException(
                        com.lightai.client.error.ErrorCode.FIELD_VALIDATION_FAILED, "请求体不能为空");
            }
        }
        try {
            return com.lightai.client.json.ProtocolJson.strictCommands().readValue(body, type);
        } catch (com.lightai.client.error.LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new com.lightai.client.error.LightAiException(
                    com.lightai.client.error.ErrorCode.FIELD_VALIDATION_FAILED,
                    "请求体解析失败: " + e.getClass().getSimpleName());
        }
    }

    public static org.springframework.http.ResponseEntity<String> ok(Object data) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.ok(data));
    }

    public static org.springframework.http.ResponseEntity<String> created(Object data) {
        return org.springframework.http.ResponseEntity.status(201)
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.ok(data));
    }

    public static org.springframework.http.ResponseEntity<String> accepted(Object data) {
        return org.springframework.http.ResponseEntity.status(202)
                .header("Content-Type", ManagementResponses.APPLICATION_JSON)
                .body(ManagementResponses.ok(data));
    }
}
