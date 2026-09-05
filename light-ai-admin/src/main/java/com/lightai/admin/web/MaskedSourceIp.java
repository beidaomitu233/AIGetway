package com.lightai.admin.web;

/**
 * 来源 IP 安全摘要：只保留前缀，不做完整记录
 * （PROJECT_DOCUMENT：client_ip 记录默认关闭，审计仅存掩码摘要）。
 */
public final class MaskedSourceIp {

    private MaskedSourceIp() {
    }

    /** IPv4 保留前三段（203.0.113.*）；IPv6 保留前 4 组；无法解析时返回 "unknown"。 */
    public static String mask(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }
        String address = remoteAddr.strip();
        if (address.contains("/")) {
            address = address.substring(0, address.indexOf('/'));
        }
        if (address.contains(".")) {
            int lastDot = address.lastIndexOf('.');
            return address.substring(0, lastDot) + ".*";
        }
        String[] groups = address.split(":", -1);
        if (groups.length >= 5) {
            return String.join(":", java.util.Arrays.copyOfRange(groups, 0, 4)) + ":*";
        }
        return "unknown";
    }
}
