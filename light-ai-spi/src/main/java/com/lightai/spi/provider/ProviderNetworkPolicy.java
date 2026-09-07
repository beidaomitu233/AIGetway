package com.lightai.spi.provider;

import java.net.InetAddress;

/**
 * 运行期外部目标地址策略（PROJECT_DOCUMENT 第 6 节 SSRF 约束）：
 * Adapter 在每次实际连接前解析目标并逐地址复核，阻断回环、私网、链路本地与组播目标，
 * 防范 DNS 重绑定。默认拒绝内部网段；内部目标需部署显式许可。
 */
@FunctionalInterface
public interface ProviderNetworkPolicy {

    boolean allow(InetAddress address);

    /** 默认策略：拒绝回环、私网、链路本地、站点本地与组播地址。 */
    static ProviderNetworkPolicy denyInternalNetworks() {
        return address -> !isRestricted(address);
    }

    /** 显式许可内部网段的部署策略（自建模型服务、内网网关）。 */
    static ProviderNetworkPolicy allowAllNetworks() {
        return address -> true;
    }

    static boolean isRestricted(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }
}
