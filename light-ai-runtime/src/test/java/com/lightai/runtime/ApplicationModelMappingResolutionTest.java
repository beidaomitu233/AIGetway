package com.lightai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.runtime.ports.AccessTokenPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationModelMappingResolutionTest {
    @Test
    void publicModelNameResolvesToAuthorizedSnapshotAlias() {
        AccessTokenPort.Principal principal = AccessTokenPort.Principal.enterprise(
                "app", List.of("legacy-alias"), "app-id", "key-id", null, null,
                Map.of(), Map.of("assistant", "legacy-alias"));

        assertThat(principal.aliasAllowed("assistant")).isTrue();
        assertThat(principal.resolveAlias("assistant")).isEqualTo("legacy-alias");
        assertThat(principal.aliasAllowed("unknown")).isFalse();
    }
}
