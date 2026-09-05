package com.lightai.admin.web;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.RolePermissions;
import java.util.Set;

/**
 * 管理接口操作权限检查（BE-002）：基于角色权限矩阵（PROJECT_DOCUMENT 2.4.2，C-022 点分码）。
 * 数据范围（application_scope、授权 Alias）由各服务另行限制；此处统一操作级判定。
 */
public final class ManagementAuthorizer {

    private ManagementAuthorizer() {
    }

    /** 无权限抛 ACCESS_DENIED；未认证上下文同样拒绝（拦截器已先行拦截）。 */
    public static void require(RequestContext context, String permission) {
        if (context == null || !context.authContext().authenticated()) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        Set<String> roles = Set.copyOf(context.authContext().roles());
        if (!RolePermissions.permissionsFor(roles).contains(permission)) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权执行该操作: " + permission);
        }
    }
}
