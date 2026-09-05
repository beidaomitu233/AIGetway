package com.lightai.client.bootstrap;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /admin/bootstrap 响应（BACKEND_PLAN 2.1 补充 API，C-011）。
 * 为前端提供身份、权限、基础路径与草稿状态；不含任何秘密。
 * adapters 仅在部署装配了 Adapter 元数据来源时输出（已加载 Adapter 的
 * 非敏感不可变声明），无加载项时省略该字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BootstrapPayload(
        BootstrapUser user,
        List<String> roles,
        List<String> permissions,
        List<String> applicationScope,
        List<String> allowedAliasIds,
        String runtimeMode,
        String uiBasePath,
        String adminApiBasePath,
        String timezone,
        long currentSnapshotNo,
        long draftRevision,
        int draftChangeCount,
        String csrfToken,
        List<AdapterDeclaration> adapters) {

    public BootstrapPayload {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        applicationScope = applicationScope == null ? List.of() : List.copyOf(applicationScope);
        allowedAliasIds = allowedAliasIds == null ? List.of() : List.copyOf(allowedAliasIds);
        adapters = adapters == null ? null : List.copyOf(adapters);
    }
}
