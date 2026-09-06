package com.lightai.server.logging;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 安全日志脱敏处理器（PRD 4.6.4.5，6.4，BE-057）：
 * 1. 严格过滤 Authorization、Cookie、数据库/Redis 密码、Provider 凭证原文、完整 secret_ref 和消息正文；
 * 2. 保证日志中只出现脱敏占位符 [MASKED]，绝无敏感信息明文泄漏。
 */
public final class SecurityLogSanitizer {

    private SecurityLogSanitizer() {}

    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "api-key", "secret-key", "proxy-authorization"
    );

    private static final Pattern BEARER_PATTERN = Pattern.compile("Bearer\\s+[A-Za-z0-9_\\-\\.]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(?i)(api[_-]?key|secret[_-]?key|password)\\s*[:=]\\s*[\"']?([^\"'\\s,;]+)[\"']?");
    private static final Pattern SECRET_REF_PATTERN = Pattern.compile("(?i)(secret_ref)\\s*[:=]\\s*[\"']?([^\"'\\s,;]+)[\"']?");
    private static final Pattern OPENAI_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9]{16,}");
    private static final Pattern MESSAGE_CONTENT_PATTERN = Pattern.compile("(?i)\"content\"\\s*:\\s*\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"");

    /**
     * 文本内容脱敏
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [MASKED]");
        result = OPENAI_KEY_PATTERN.matcher(result).replaceAll("sk-[MASKED]");
        result = API_KEY_PATTERN.matcher(result).replaceAll("$1: [MASKED]");
        result = SECRET_REF_PATTERN.matcher(result).replaceAll("$1: [MASKED]");
        result = MESSAGE_CONTENT_PATTERN.matcher(result).replaceAll("\"content\":\"[BODY_MASKED]\"");
        return result;
    }

    /**
     * HTTP 头部安全脱敏（大小写无关）
     */
    public static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        if (headers == null) return Map.of();
        Map<String, String> sanitized = new LinkedHashMap<>();
        headers.forEach((key, val) -> {
            if (key != null && SENSITIVE_HEADER_NAMES.contains(key.toLowerCase(Locale.ROOT))) {
                sanitized.put(key, "[MASKED]");
            } else {
                sanitized.put(key, sanitize(val));
            }
        });
        return Collections.unmodifiableMap(sanitized);
    }

    /**
     * 检查文本是否包含敏感泄漏特征
     */
    public static boolean containsRawSecret(String text) {
        if (text == null || text.isEmpty()) return false;
        if (OPENAI_KEY_PATTERN.matcher(text).find()) return true;
        if (text.contains("Bearer ") && !text.contains("Bearer [MASKED]")) return true;
        return false;
    }
}
