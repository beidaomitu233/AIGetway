package com.lightai.spi.provider;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 部署级外部目标地址策略持有者：由 Standalone Server 或宿主按配置装配一次，
 * 未装配时保持默认拒绝内部网段，与管理端保存校验口径一致。
 */
public final class ProviderNetworkPolicies {

    private static final AtomicReference<ProviderNetworkPolicy> CURRENT =
            new AtomicReference<>(ProviderNetworkPolicy.denyInternalNetworks());

    private ProviderNetworkPolicies() {
    }

    public static ProviderNetworkPolicy current() {
        return CURRENT.get();
    }

    /** 按部署配置装配：允许内部网段时放行全部目标，否则保持默认拒绝。 */
    public static void configure(boolean allowInternalNetworks) {
        CURRENT.set(allowInternalNetworks
                ? ProviderNetworkPolicy.allowAllNetworks()
                : ProviderNetworkPolicy.denyInternalNetworks());
    }

    /** 测试与宿主自定义装配入口。 */
    public static void set(ProviderNetworkPolicy policy) {
        CURRENT.set(policy == null ? ProviderNetworkPolicy.denyInternalNetworks() : policy);
    }
}
