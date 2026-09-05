package com.lightai.client.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiCatalogTest {

    @Test
    void noDuplicateMethodAndPath() {
        assertThat(ApiCatalog.findConflicts()).isEmpty();
    }

    @Test
    void bootstrapEndpointIsDeclared() {
        List<ApiCatalog.ApiEndpoint> endpoints = ApiCatalog.all();
        assertThat(endpoints).anySatisfy(endpoint -> {
            assertThat(endpoint.method()).isEqualTo("GET");
            assertThat(endpoint.path()).isEqualTo("/admin/bootstrap");
        });
    }

    @Test
    void staticSegmentsDeclaredBeforeIdRoutes() {
        List<String> reliabilityPaths = ApiCatalog.all().stream()
                .filter(endpoint -> endpoint.path().startsWith("/admin/reliability-policies"))
                .map(ApiCatalog.ApiEndpoint::path)
                .toList();
        assertThat(reliabilityPaths.indexOf("/admin/reliability-policies/default"))
                .isLessThan(reliabilityPaths.indexOf("/admin/reliability-policies/{id}"));

        List<String> auditPaths = ApiCatalog.all().stream()
                .filter(endpoint -> endpoint.path().startsWith("/admin/audit-logs"))
                .map(ApiCatalog.ApiEndpoint::path)
                .toList();
        assertThat(auditPaths.indexOf("/admin/audit-logs/export"))
                .isLessThan(auditPaths.indexOf("/admin/audit-logs/{id}"));
    }

    @Test
    void normalizesTemplateVariableNames() {
        assertThat(ApiCatalog.normalize("/admin/credential-pools/{poolId}/credentials"))
                .isEqualTo("/admin/credential-pools/{}/credentials");
    }
}
