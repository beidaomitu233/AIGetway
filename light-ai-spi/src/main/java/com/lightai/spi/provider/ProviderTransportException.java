package com.lightai.spi.provider;

/**
 * Adapter 传输失败（4.7.2.3）：携带 ProviderFailure 供 classifyError 分类；
 * Runtime 可见此类型以进入恢复决策（provider-common 的异常继承本类型）。
 */
public class ProviderTransportException extends RuntimeException {

    private final ProviderFailure failure;

    public ProviderTransportException(ProviderFailure failure, Throwable cause) {
        super(failure.kind() + ": " + failure.safeMessage(), cause);
        this.failure = failure;
    }

    public ProviderFailure failure() {
        return failure;
    }
}
