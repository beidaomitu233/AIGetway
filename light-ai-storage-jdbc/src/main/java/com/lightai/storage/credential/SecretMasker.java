package com.lightai.storage.credential;

/**
 * 秘密掩码生成（服务端生成安全掩码，页面仅展示掩码）。
 * 规则：保留末 4 位可见，其余以 **** 覆盖；不足 8 位整体掩码。
 */
public final class SecretMasker {

    private SecretMasker() {
    }

    public static String mask(char[] plaintext) {
        if (plaintext == null || plaintext.length == 0) {
            return "****";
        }
        String value = new String(plaintext);
        if (value.length() < 8) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }

    /** EXTERNAL_REF 展示：仅前缀与固定后缀，不含完整可定位路径。 */
    public static String maskRef(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return null;
        }
        int schemeEnd = secretRef.indexOf("://");
        String scheme = schemeEnd > 0 ? secretRef.substring(0, schemeEnd + 3) : "";
        return scheme + "…(external)";
    }
}
