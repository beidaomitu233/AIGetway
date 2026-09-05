package com.lightai.admin.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求关联 ID：优先采用调用方 X-Request-Id（前端已发送），否则生成 UUID。
 * request_id 用于错误响应与审计定位，不进入日志正文。
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                    jakarta.servlet.http.HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(HEADER, requestId);
        filterChain.doFilter(request, response);
    }

    /** 过滤器读取，供拦截器与错误处理复用。 */
    public static String requestIdOf(jakarta.servlet.http.HttpServletRequest request) {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            return null;
        }
        return requestId;
    }
}
