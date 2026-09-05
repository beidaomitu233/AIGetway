package com.lightai.spi.provider;

/**
 * Provider 失败输入（4.7.2.5）：Adapter 按条件分类，输出统一错误码与恢复属性；
 * 分类为纯函数，不访问网络与持久化状态。
 */
public record ProviderFailure(
        Integer httpStatus,
        Kind kind,
        String providerRequestId,
        String safeMessage) {

    public enum Kind {
        NETWORK,
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        FIRST_TOKEN_TIMEOUT,
        BAD_RESPONSE,
        HTTP_STATUS
    }

    public ProviderFailure {
        safeMessage = safeMessage == null ? "" : safeMessage;
    }

    public static ProviderFailure http(int status, String providerRequestId, String safeMessage) {
        return new ProviderFailure(status, Kind.HTTP_STATUS, providerRequestId, safeMessage);
    }

    public static ProviderFailure network(String safeMessage) {
        return new ProviderFailure(null, Kind.NETWORK, null, safeMessage);
    }

    public static ProviderFailure connectTimeout(String safeMessage) {
        return new ProviderFailure(null, Kind.CONNECT_TIMEOUT, null, safeMessage);
    }

    public static ProviderFailure firstTokenTimeout(String safeMessage) {
        return new ProviderFailure(null, Kind.FIRST_TOKEN_TIMEOUT, null, safeMessage);
    }

    public static ProviderFailure badResponse(String safeMessage) {
        return new ProviderFailure(null, Kind.BAD_RESPONSE, null, safeMessage);
    }

    /** 判定安全摘要是否包含任一关键字（用于模型不存在/参数拒绝等语义细分）。 */
    public boolean containsAny(String... needles) {
        for (String needle : needles) {
            if (safeMessage != null && safeMessage.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
