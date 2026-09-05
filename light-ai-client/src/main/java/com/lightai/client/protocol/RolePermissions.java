package com.lightai.client.protocol;

import java.util.List;
import java.util.Set;

/**
 * 角色→功能权限矩阵（PROJECT_DOCUMENT 2.4.2）。
 * 权限同时约束页面入口、API 操作与字段返回；后端鉴权不依赖前端隐藏按钮。
 * 数据范围（application_scope、授权 Alias、本应用 Trace）由运行时鉴权层另行限制，
 * 不在本矩阵内表达。
 */
public final class RolePermissions {

    private static final List<String> VIEW_ONLY = List.of(
            Permissions.OVERVIEW_VIEW,
            Permissions.PROVIDER_VIEW,
            Permissions.MODEL_VIEW,
            Permissions.ALIAS_VIEW,
            Permissions.LIMIT_VIEW,
            Permissions.RELIABILITY_VIEW,
            Permissions.CIRCUIT_VIEW,
            Permissions.TRACE_VIEW,
            Permissions.USAGE_VIEW,
            Permissions.DRAFT_VIEW,
            Permissions.RUNTIME_CONFIG_VIEW,
            Permissions.DEVELOPER_VIEW);

    private static final List<String> OPERATOR = List.of(
            // VIEW_ONLY 全部权限，另外具备检测与人工恢复能力
            Permissions.OVERVIEW_VIEW,
            Permissions.PROVIDER_VIEW, Permissions.PROVIDER_CHECK,
            Permissions.CREDENTIAL_VIEW, Permissions.CREDENTIAL_CHECK,
            Permissions.MODEL_VIEW,
            Permissions.ALIAS_VIEW,
            Permissions.LIMIT_VIEW,
            Permissions.RELIABILITY_VIEW,
            Permissions.CIRCUIT_VIEW, Permissions.CIRCUIT_OPERATE,
            Permissions.TRACE_VIEW, Permissions.TRACE_DIAGNOSTICS, Permissions.TRACE_EXPORT,
            Permissions.USAGE_VIEW, Permissions.USAGE_EXPORT,
            Permissions.DRAFT_VIEW,
            Permissions.RUNTIME_CONFIG_VIEW,
            Permissions.ACCESS_VIEW,
            Permissions.AUDIT_VIEW, Permissions.AUDIT_EXPORT,
            Permissions.DEVELOPER_VIEW, Permissions.DEVELOPER_TEST);

    private static final List<String> DEVELOPER = List.of(
            Permissions.OVERVIEW_VIEW,
            Permissions.PROVIDER_VIEW,
            Permissions.MODEL_VIEW,
            Permissions.ALIAS_VIEW,
            Permissions.LIMIT_VIEW,
            Permissions.RELIABILITY_VIEW,
            Permissions.CIRCUIT_VIEW,
            Permissions.TRACE_VIEW,
            Permissions.USAGE_VIEW,
            Permissions.DRAFT_VIEW,
            Permissions.RUNTIME_CONFIG_VIEW,
            Permissions.DEVELOPER_VIEW, Permissions.DEVELOPER_TEST);

    private RolePermissions() {
    }

    /**
     * 返回角色集合的合并权限。未知角色按空权限处理（默认拒绝），
     * 不因身份包含陌生角色而放权。
     */
    public static List<String> permissionsFor(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        java.util.TreeSet<String> merged = new java.util.TreeSet<>();
        if (roles.contains(Roles.SYSTEM_ADMIN)) {
            merged.addAll(Permissions.all());
        }
        if (roles.contains(Roles.OPERATOR)) {
            merged.addAll(OPERATOR);
        }
        if (roles.contains(Roles.DEVELOPER)) {
            merged.addAll(DEVELOPER);
        }
        if (roles.contains(Roles.VIEWER)) {
            merged.addAll(VIEW_ONLY);
        }
        return List.copyOf(merged);
    }

    /** 判定身份是否具备指定权限；未认证身份恒为 false。 */
    public static boolean has(java.util.Collection<String> permissions, String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
