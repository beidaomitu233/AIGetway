package com.lightai.admin.web;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;

/**
 * 服务层权限判定（BE-002 延伸）：权限同时约束页面入口、API 操作与字段返回；
 * 隐藏按钮不能替代后端鉴权。拒绝不产生任何 Provider 调用。
 */
public final class RequestPermissions {

    private RequestPermissions() {
    }

    public static void require(RequestContext context, String permission) {
        if (context == null || !context.authContext().authenticated()) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "管理身份未认证或无权限");
        }
        if (!com.lightai.client.protocol.RolePermissions.has(
                context.authContext().roles().isEmpty()
                        ? java.util.List.of()
                        : com.lightai.client.protocol.RolePermissions.permissionsFor(
                                java.util.Set.copyOf(context.authContext().roles())),
                permission)) {
            throw new LightAiException(ErrorCode.ACCESS_DENIED, "无权限执行该操作：" + permission);
        }
    }

    /** 便捷判定：是否具备指定权限（不抛异常）。 */
    public static boolean has(RequestContext context, String permission) {
        if (context == null || !context.authContext().authenticated()) {
            return false;
        }
        return com.lightai.client.protocol.RolePermissions.has(
                com.lightai.client.protocol.RolePermissions.permissionsFor(
                        java.util.Set.copyOf(context.authContext().roles())),
                permission);
    }
}
