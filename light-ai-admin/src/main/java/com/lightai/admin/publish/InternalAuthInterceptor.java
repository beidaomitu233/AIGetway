package com.lightai.admin.publish;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * /internal/** 实例认证拦截（BE-041）。内部接口与业务 Token、管理会话隔离；
 * 认证失败返回 INSTANCE_AUTH_FAILED（401），不暴露内部路由存在性以外的信息。
 */
public class InternalAuthInterceptor implements HandlerInterceptor {

    private final InternalInstanceAuth instanceAuth;

    public InternalAuthInterceptor(InternalInstanceAuth instanceAuth) {
        this.instanceAuth = instanceAuth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        Optional<UUID> bound = instanceAuth.authenticate(request);
        bound.ifPresent(id -> request.setAttribute(InternalInstanceAuth.ATTRIBUTE, id));
        return true;
    }
}
