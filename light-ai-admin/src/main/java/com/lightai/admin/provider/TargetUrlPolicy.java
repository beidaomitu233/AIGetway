package com.lightai.admin.provider;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Provider/代理目标地址安全策略（PROJECT_DOCUMENT 第 6 节 SSRF 约束）：
 * 仅允许 http/https、禁止 userinfo 与片段、禁止字面量回环/私网/链路本地地址；
 * 内部网段需部署显式许可（light-ai.admin.allowed-provider-internal-networks）。
 * DNS 重绑定防护在真实调用时再次解析校验（BE-P05 Adapter 职责）。
 */
public final class TargetUrlPolicy {

    private final boolean allowInternalNetworks;

    public TargetUrlPolicy(boolean allowInternalNetworks) {
        this.allowInternalNetworks = allowInternalNetworks;
    }

    /** 校验并返回规范化地址；非法目的地址返回 FIELD_VALIDATION_FAILED。 */
    public String validate(String rawUrl, String field) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw fieldError(field, "REQUIRED", field + " 必填");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (URISyntaxException e) {
            throw fieldError(field, "INVALID", field + " 不是合法 URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw fieldError(field, "INVALID", field + " 仅允许 http/https");
        }
        if (uri.getUserInfo() != null) {
            throw fieldError(field, "INVALID", field + " 不允许携带用户信息");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw fieldError(field, "INVALID", field + " 缺少主机名");
        }
        if (uri.getFragment() != null) {
            throw fieldError(field, "INVALID", field + " 不允许携带片段");
        }
        if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
            throw fieldError(field, "INVALID", field + " 端口非法");
        }
        if (!allowInternalNetworks && isInternalHost(uri.getHost())) {
            throw fieldError(field, "FORBIDDEN_TARGET", field + " 不允许指向内部网段");
        }
        return uri.toString();
    }

    static boolean isInternalHost(String host) {
        String value = host.toLowerCase().strip();
        if ("localhost".equals(value) || value.endsWith(".localhost") || value.endsWith(".local")
                || value.endsWith(".internal")) {
            return true;
        }
        return literalIpIsInternal(value);
    }

    private static boolean literalIpIsInternal(String host) {
        // 仅判断 IPv4 字面量与常见 IPv6 内部前缀；解析期防护由调用时校验补充
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            try {
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                if (first == 10 || first == 127 || first == 0) {
                    return true;
                }
                if (first == 172 && second >= 16 && second <= 31) {
                    return true;
                }
                if (first == 192 && second == 168) {
                    return true;
                }
                if (first == 169 && second == 254) {
                    return true;
                }
                return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return host.startsWith("[::1]") || host.startsWith("[fc") || host.startsWith("[fd")
                || host.startsWith("[fe80");
    }

    private static LightAiException fieldError(String field, String code, String message) {
        return new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "目标地址不合法",
                java.util.List.of(new com.lightai.client.error.FieldIssue(field, code, message)));
    }
}
