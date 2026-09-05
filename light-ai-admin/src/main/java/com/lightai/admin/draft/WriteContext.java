package com.lightai.admin.draft;

/**
 * 配置写请求上下文（BE-002/006）：由 Controller 从 RequestContext 提取，
 * 传入各配置写服务，避免服务直接依赖 Servlet API。
 */
public record WriteContext(String requestId, String operatorId, String sourceMode, String sourceIpMasked) {

    public WriteContext {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("request_id 必填");
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operator_id 必填");
        }
    }
}
