package com.lightai.admin.web;

import com.lightai.spi.auth.AuthContext;

/**
 * 请求级上下文：AuthContext 每请求建立一次（BACKEND_PLAN 2.3），
 * request_id 贯穿错误响应与审计关联。
 */
public final class RequestContext {

    public static final String ATTRIBUTE = "com.lightai.admin.requestContext";

    private final AuthContext authContext;
    private final String requestId;
    private final String sourceIpMasked;

    public RequestContext(AuthContext authContext, String requestId, String sourceIpMasked) {
        this.authContext = authContext;
        this.requestId = requestId;
        this.sourceIpMasked = sourceIpMasked;
    }

    public AuthContext authContext() {
        return authContext;
    }

    public String requestId() {
        return requestId;
    }

    public String sourceIpMasked() {
        return sourceIpMasked;
    }
}
