package com.lightai.admin.web;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.UnifiedError;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理入口统一鉴权（BE-002）：逐请求通过部署认证适配建立 AuthContext。
 * 未认证默认 403 ACCESS_DENIED 并交部署登录提示； Embedded 无适配时由
 * denyAll 缺省实现兜底，不存在匿名入口。
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AuthContextProvider authContextProvider;

    public AdminAuthInterceptor(AuthContextProvider authContextProvider) {
        this.authContextProvider = authContextProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        request.setAttribute(RequestStart.ATTRIBUTE, System.nanoTime());
        AuthRequest authRequest = new AuthRequest(
                request.getMethod(),
                request.getRequestURI(),
                headerMap(request),
                request.getRemoteAddr());
        AuthContext authContext = authContextProvider.resolve(authRequest);

        String requestId = RequestIdFilter.requestIdOf(request);
        RequestContext context = new RequestContext(
                authContext,
                requestId == null ? java.util.UUID.randomUUID().toString() : requestId,
                MaskedSourceIp.mask(request.getRemoteAddr()));
        request.setAttribute(RequestContext.ATTRIBUTE, context);

        if (!authContext.authenticated()) {
            writeAccessDenied(response, context.requestId());
            return false;
        }
        return true;
    }

    private static Map<String, String> headerMap(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(), request.getHeader(name));
        }
        return headers;
    }

    static void writeAccessDenied(HttpServletResponse response, String requestId) throws IOException {
        UnifiedError error = UnifiedError.builder(ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限")
                .requestId(requestId)
                .build();
        response.setStatus(ErrorCode.ACCESS_DENIED.httpStatus());
        response.setContentType(ManagementResponses.APPLICATION_JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ManagementResponses.error(error));
    }

    /** 请求起点，供耗时日志使用。 */
    public static final class RequestStart {
        public static final String ATTRIBUTE = "com.lightai.admin.requestStart";

        private RequestStart() {
        }
    }
}
