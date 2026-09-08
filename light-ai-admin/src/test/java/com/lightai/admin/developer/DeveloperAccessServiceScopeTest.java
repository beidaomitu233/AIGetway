package com.lightai.admin.developer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.spi.auth.AuthContext;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeveloperAccessServiceScopeTest {
    private final ConfigSnapshotPort snapshots = () -> new ConfigSnapshotPort.ActiveSnapshot(3, List.of(
            new AliasView("alias-a-id", "alias-a", "A", true, List.of()),
            new AliasView("alias-b-id", "alias-b", "B", true, List.of())));
    private final DeveloperAccessService service = new DeveloperAccessService(
            snapshots, Optional::empty, null, "STANDALONE_SERVER", "http://localhost", Clock.systemUTC());

    @Test void developerApplicationScopeDoesNotGrantAliasAccess() {
        RequestContext context = context(List.of("alias-a"), List.of());
        assertThat(service.context(context, null).publishedAliases()).isEmpty();
        assertThatThrownBy(() -> service.codeSample(context, "alias-a", "curl", false))
                .isInstanceOf(LightAiException.class);
    }

    @Test void developerSeesOnlyExplicitAliasScope() {
        RequestContext context = context(List.of("orders-app"), List.of("alias-a-id"));
        assertThat(service.codeSample(context, "alias-a", "curl", false).alias()).isEqualTo("alias-a");
        assertThatThrownBy(() -> service.codeSample(context, "alias-b", "curl", false))
                .isInstanceOf(LightAiException.class);
    }

    private static RequestContext context(List<String> applications, List<String> aliases) {
        return new RequestContext(AuthContext.authenticated("dev-1", "开发人员", Set.of("DEVELOPER"),
                applications, aliases), "req-1", null);
    }
}