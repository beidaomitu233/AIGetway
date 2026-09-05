package com.lightai.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 会话 CSRF Token（C-022 请求头 X-CSRF-Token）。
 * 仅在部署使用 Cookie 会话认证时启用；Token 只进内存会话，
 * 不进入 URL、日志与浏览器持久存储。
 */
public final class CsrfTokenService {

    public static final String HEADER = "X-CSRF-Token";
    private static final String SESSION_ATTRIBUTE = "com.lightai.admin.csrfToken";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 读取或按需生成会话 Token；bootstrap 输出后由前端写请求回传。 */
    public String currentToken(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object token = session.getAttribute(SESSION_ATTRIBUTE);
        if (token instanceof String value && !value.isBlank()) {
            return value;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(SESSION_ATTRIBUTE, generated);
        return generated;
    }

    /** 校验请求回传值；常量时间比较避免侧信道。 */
    public boolean matches(HttpSession session, String provided) {
        if (session == null || provided == null || provided.isBlank()) {
            return false;
        }
        Object expected = session.getAttribute(SESSION_ATTRIBUTE);
        return expected instanceof String value && constantTimeEquals(value, provided);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
