package com.lightai.admin.publish;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 部署端为每个实例配置独立口令；请求中的 ID 仅用于与认证身份核对。 */
public final class InternalInstanceAuth {
    public static final String ATTRIBUTE = "com.lightai.admin.internalInstanceId";
    public static final String TOKEN_HEADER = "X-Light-AI-Instance-Token";
    public static final String INSTANCE_ID_HEADER = "X-Light-AI-Instance-Id";
    private final Map<UUID, String> credentials;

    /** 旧共享口令无法证明实例身份，保留构造器但默认拒绝。 */
    public InternalInstanceAuth(String ignoredSharedToken) {
        this(Map.of());
    }

    public InternalInstanceAuth(Map<UUID, String> credentials) {
        this.credentials = Map.copyOf(credentials);
        if (this.credentials.values().stream().anyMatch(String::isBlank)
                || this.credentials.values().stream().distinct().count() != this.credentials.size()) {
            throw new IllegalArgumentException("内部实例必须配置非空且互不相同的口令");
        }
    }

    public Optional<UUID> authenticate(HttpServletRequest request) {
        String presented = request.getHeader(TOKEN_HEADER);
        UUID identity = null;
        if (presented != null) {
            for (var entry : credentials.entrySet()) {
                if (constantTimeEquals(presented, entry.getValue())) identity = entry.getKey();
            }
        }
        if (identity == null) throw denied();
        String claimed = request.getHeader(INSTANCE_ID_HEADER);
        if (claimed != null && !identity.toString().equalsIgnoreCase(claimed.trim())) throw denied();
        return Optional.of(identity);
    }

    public static UUID requireIdentity(HttpServletRequest request, String claimedInstanceId) {
        Object bound = request.getAttribute(ATTRIBUTE);
        if (bound instanceof UUID boundId && claimedInstanceId != null
                && boundId.toString().equalsIgnoreCase(claimedInstanceId.trim())) return boundId;
        throw denied();
    }

    private static LightAiException denied() {
        return new LightAiException(ErrorCode.INSTANCE_AUTH_FAILED, "内部实例认证身份不匹配或未配置");
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
