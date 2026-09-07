package com.lightai.server.runtime;

import com.lightai.client.protocol.Roles;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.auth.AuthContextProvider;
import com.lightai.spi.auth.AuthRequest;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

/**
 * 部署身份适配（PROJECT_DOCUMENT 第 6 节 / C-001 Standalone 身份扩展点）：
 * 两种显式启用的身份来源，均默认关闭；两者都未启用时所有管理请求 403（默认拒绝）。
 * <ul>
 *   <li>部署令牌：{@code light-ai.server.auth.admin-token} 配置后，携带
 *       {@code X-Admin-Token} 且值匹配的请求获得 SYSTEM_ADMIN 身份；</li>
 *   <li>本机信任：{@code light-ai.server.auth.trusted-local=true} 时，回环地址请求
 *       获得 SYSTEM_ADMIN 身份，仅用于本机管理场景。</li>
 * </ul>
 * 令牌与认证头不写入日志、审计或 Trace；身份解析不创建会话。
 */
public final class DeploymentAuthContextProvider implements AuthContextProvider {

    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final String adminToken;
    private final boolean trustedLocal;

    public DeploymentAuthContextProvider(String adminToken, boolean trustedLocal) {
        this.adminToken = adminToken == null || adminToken.isBlank() ? null : adminToken.strip();
        this.trustedLocal = trustedLocal;
    }

    @Override
    public AuthContext resolve(AuthRequest request) {
        if (adminToken != null) {
            String presented = request.headers().get(ADMIN_TOKEN_HEADER.toLowerCase());
            if (presented == null) {
                presented = request.headers().get(ADMIN_TOKEN_HEADER);
            }
            if (presented != null && constantTimeEquals(presented.strip(), adminToken)) {
                return systemAdmin("deployment-admin");
            }
        }
        if (trustedLocal && isLoopback(request.remoteAddress())) {
            return systemAdmin("local-admin");
        }
        return AuthContext.anonymous();
    }

    private static AuthContext systemAdmin(String userId) {
        return AuthContext.authenticated(userId, "系统管理员",
                Set.of(Roles.SYSTEM_ADMIN), List.of("*"));
    }

    private static boolean isLoopback(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        String addr = remoteAddress.strip();
        if (addr.startsWith("[") && addr.endsWith("]")) {
            addr = addr.substring(1, addr.length() - 1);
        }
        try {
            InetAddress target = InetAddress.getByName(addr);
            return target.isLoopbackAddress() || target.isAnyLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
