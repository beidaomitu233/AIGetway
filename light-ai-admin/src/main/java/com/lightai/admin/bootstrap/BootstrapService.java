package com.lightai.admin.bootstrap;

import com.lightai.admin.AdminProperties;
import com.lightai.admin.storage.ManagementStateReader;
import com.lightai.client.bootstrap.AdapterDeclaration;
import com.lightai.client.bootstrap.BootstrapPayload;
import com.lightai.client.bootstrap.BootstrapUser;
import com.lightai.client.protocol.RolePermissions;
import com.lightai.client.protocol.Roles;
import com.lightai.spi.adapter.AdapterMetadataSource;
import com.lightai.spi.auth.AuthContext;
import java.util.List;
import java.util.Set;

/**
 * Bootstrap 组装（BE-002/C-011）：身份、权限矩阵、基础路径、草稿状态与已加载 Adapter 声明。
 * 不返回任何秘密；无 Adapter 元数据来源时省略 adapters 字段。
 */
public class BootstrapService {

    private final AdminProperties properties;
    private final ManagementStateReader managementStateReader;
    private final AdapterMetadataSource adapterMetadataSource;

    public BootstrapService(AdminProperties properties, ManagementStateReader managementStateReader,
                            AdapterMetadataSource adapterMetadataSource) {
        this.properties = properties;
        this.managementStateReader = managementStateReader;
        this.adapterMetadataSource = adapterMetadataSource;
    }

    public BootstrapPayload build(AuthContext authContext, String csrfToken) {
        Set<String> roles = Set.copyOf(authContext.roles());
        List<String> permissions = RolePermissions.permissionsFor(roles);
        ManagementStateReader.ManagementState state = managementStateReader.read();

        List<AdapterDeclaration> adapters = adapterMetadataSource == null ? null : adapterMetadataSource.declarations();
        List<AdapterDeclaration> adapterPayload = adapters == null || adapters.isEmpty() ? null : adapters;

        return new BootstrapPayload(
                new BootstrapUser(authContext.userId(), authContext.displayName()),
                authContext.roles(),
                permissions,
                authContext.applicationScope(),
                List.of(),
                properties.getRuntimeMode(),
                properties.getUiBasePath(),
                properties.getAdminApiBasePath(),
                state.timezone(),
                state.currentSnapshotNo(),
                state.draftRevision(),
                state.draftChangeCount(),
                csrfToken,
                adapterPayload);
    }

    /** 验证部署配置的 runtime_mode 属于冻结枚举（C-003）。 */
    public void validateConfiguration() {
        try {
            com.lightai.client.protocol.RuntimeMode.valueOf(properties.getRuntimeMode());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("light-ai.admin.runtime-mode 非法：" + properties.getRuntimeMode());
        }
    }

    /** 角色码是否为已知角色；未知角色仍可登录但无权限（默认拒绝）。 */
    static boolean knownRole(String role) {
        return Roles.SYSTEM_ADMIN.equals(role) || Roles.OPERATOR.equals(role)
                || Roles.DEVELOPER.equals(role) || Roles.VIEWER.equals(role);
    }
}
