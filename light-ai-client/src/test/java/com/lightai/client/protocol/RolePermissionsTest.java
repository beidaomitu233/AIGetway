package com.lightai.client.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RolePermissionsTest {

    @Test
    void systemAdminHasEveryDeclaredPermission() {
        List<String> permissions = RolePermissions.permissionsFor(Set.of(Roles.SYSTEM_ADMIN));
        assertThat(permissions).containsAll(Permissions.all());
        assertThat(Permissions.all()).hasSize(permissions.size());
    }

    @Test
    void operatorCanOperateCircuitAndCheckButNotManageConfig() {
        List<String> permissions = RolePermissions.permissionsFor(Set.of(Roles.OPERATOR));
        assertThat(permissions).contains(Permissions.PROVIDER_CHECK, Permissions.CREDENTIAL_CHECK,
                Permissions.CIRCUIT_OPERATE, Permissions.TRACE_DIAGNOSTICS,
                Permissions.TRACE_EXPORT, Permissions.AUDIT_EXPORT, Permissions.ACCESS_VIEW);
        assertThat(permissions).doesNotContain(Permissions.PROVIDER_MANAGE, Permissions.PUBLISH_MANAGE,
                Permissions.MODEL_IMPORT, Permissions.ACCESS_MANAGE, Permissions.DRAFT_REVERT);
    }

    @Test
    void developerIsViewOnlyPlusDeveloperTools() {
        List<String> permissions = RolePermissions.permissionsFor(Set.of(Roles.DEVELOPER));
        assertThat(permissions).contains(Permissions.DEVELOPER_TEST, Permissions.TRACE_VIEW);
        assertThat(permissions).doesNotContain(Permissions.TRACE_DIAGNOSTICS, Permissions.TRACE_EXPORT,
                Permissions.PROVIDER_CHECK, Permissions.CIRCUIT_OPERATE, Permissions.ACCESS_VIEW);
    }

    @Test
    void viewerCannotExecuteAnything() {
        List<String> permissions = RolePermissions.permissionsFor(Set.of(Roles.VIEWER));
        for (String permission : permissions) {
            assertThat(permission).endsWith(".view");
        }
        assertThat(permissions).contains(Permissions.OVERVIEW_VIEW, Permissions.DEVELOPER_VIEW);
        assertThat(permissions).doesNotContain(Permissions.DEVELOPER_TEST);
    }

    @Test
    void unknownRoleYieldsDefaultDeny() {
        assertThat(RolePermissions.permissionsFor(Set.of("SUPERUSER"))).isEmpty();
        assertThat(RolePermissions.permissionsFor(Set.of())).isEmpty();
        assertThat(RolePermissions.permissionsFor(null)).isEmpty();
    }

    @Test
    void mergedRolesUnionPermissions() {
        List<String> permissions = RolePermissions.permissionsFor(Set.of(Roles.OPERATOR, Roles.VIEWER));
        assertThat(permissions).contains(Permissions.CIRCUIT_OPERATE, Permissions.OVERVIEW_VIEW);
    }
}
