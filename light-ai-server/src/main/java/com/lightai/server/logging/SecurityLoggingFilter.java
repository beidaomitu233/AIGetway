package com.lightai.server.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 访问日志安全脱敏过滤器（PRD 4.6.4.5，BE-057）：
 * 记录 X-Trace-Id、HTTP 动词、路径与耗时，严禁输出未经脱敏的敏感信息。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class SecurityLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String traceId = request.getHeader("X-Trace-Id");
        String uri = request.getRequestURI();
        String method = request.getMethod();

        log.debug("HTTP Request started: method={}, uri={}, trace_id={}", method, uri, traceId != null ? traceId : "-");

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            int status = response.getStatus();
            log.info("HTTP Request completed: method={}, uri={}, status={}, duration_ms={}, trace_id={}",
                    method, uri, status, duration, traceId != null ? traceId : "-");
        }
    }
}
