package com.lightai.admin.publish;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * 内部实例认证（BE-041）。内部接口使用部署提供的实例服务身份（4.5.6.5），
 * 与业务 Token、管理会话隔离；默认实现为部署配置共享口令，
 * 未配置口令时一律拒绝（PROJECT_DOCUMENT 第 6 节默认拒绝匿名）。
 * 身份绑定（mTLS 客户端证书）由部署侧提供后替换本实现。
 */
public final class InternalInstanceAuth {

    public static final String ATTRIBUTE = "com.lightai.admin.internalInstanceId";
    public static final String TOKEN_HEADER = "X-Light-AI-Instance-Token";
    public static final String INSTANCE_ID_HEADER = "X-Light-AI-Instance-Id";

    private final String configuredToken;

    public InternalInstanceAuth(String configuredToken) {
        this.configuredToken = configuredToken;
    }

    /** 校验请求并返回已绑定的实例身份；支持 X-Light-AI-Instance-Id 头或 token:instance_id 格式绑定。 */
    public Optional<UUID> authenticate(HttpServletRequest request) {
        if (configuredToken == null || configuredToken.isBlank()) {
            throw new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "内部实例认证未配置，拒绝访问");
        }
        String presented = request.getHeader(TOKEN_HEADER);
        if (presented == null) {
            throw new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "内部实例认证失败");
        }
        UUID boundInstanceId = null;
        if (presented.contains(":")) {
            String[] parts = presented.split(":", 2);
            if (!constantTimeEquals(parts[0], configuredToken)) {
                throw new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "内部实例认证失败");
            }
            try {
                boundInstanceId = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException e) {
                throw new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "内部实例 ID 不合法");
            }
        } else {
            if (!constantTimeEquals(presented, configuredToken)) {
                throw new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "内部实例认证失败");
            }
        }

        String instanceIdHeader = request.getHeader(INSTANCE_ID_HEADER);
        if (instanceIdHeader != null && !instanceIdHeader.isBlank()) {
            try {
                UUID headerId = UUID.fromString(instanceIdHeader.trim());
                if (boundInstanceId != null && !boundInstanceId.equals(headerId)) {
                    throw new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "实例 Token 身份与头部不一致");
                }
                boundInstanceId = headerId;
            } catch (IllegalArgumentException e) {
                throw new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "内部实例 ID 不合法");
            }
        }
        return Optional.ofNullable(boundInstanceId);
    }

    /** 路径/请求体 instance_id 与认证身份一致性校验；必须已绑定且一致。 */
    public static UUID requireIdentity(HttpServletRequest request, String claimedInstanceId) {
        Object bound = request.getAttribute(ATTRIBUTE);
        if (bound instanceof UUID boundId) {
            if (claimedInstanceId == null || !boundId.toString().equalsIgnoreCase(claimedInstanceId.trim())) {
                throw new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "instance_id 与认证身份不一致");
            }
            return boundId;
        }
        throw new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "未绑定实例认证身份，拒绝上报");
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = right.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
