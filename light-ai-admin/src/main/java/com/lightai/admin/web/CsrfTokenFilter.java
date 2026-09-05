package com.lightai.admin.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cookie 会话写请求 CSRF 检查（PROJECT_DOCUMENT 第 6 节）。
 * 仅在部署声明启用时注册；写方法必须携带与会话一致的 X-CSRF-Token。
 */
public class CsrfTokenFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final CsrfTokenService csrfTokenService;

    public CsrfTokenFilter(CsrfTokenService csrfTokenService) {
        this.csrfTokenService = csrfTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (PROTECTED_METHODS.contains(request.getMethod())) {
            if (!csrfTokenService.matches(request.getSession(false), request.getHeader(CsrfTokenService.HEADER))) {
                AdminAuthInterceptor.writeAccessDenied(response, RequestIdFilter.requestIdOf(request));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
