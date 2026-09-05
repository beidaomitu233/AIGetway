package com.lightai.admin.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.access.AccessTestSupport.FakeAuditRepository;
import com.lightai.admin.impact.ImpactService;
import com.lightai.client.access.ImpactAnalysis;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.storage.access.ConfigReferenceQuery;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ImpactService 语义（BE-010/012/014/016）：摘要稳定、引用变化即过期、阻断判定。 */
class ImpactServiceTest {

    private final Connection connection = AccessTestSupport.proxyConnection();

    @Test
    void providerImpactListsReferencesAndBlocksDelete() {
        UUID providerId = UUID.randomUUID();
        UUID poolId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        ImpactService service = new ImpactService(new StaticReferenceQuery(
                List.of(new ConfigReferenceQuery.EntitySummary(poolId, "pool-1", "CREDENTIAL_POOL")),
                List.of(new ConfigReferenceQuery.EntitySummary(modelId, "gpt-x", "PROVIDER_MODEL")),
                List.of(), List.of()));

        ImpactAnalysis analysis = service.analyze(connection, "PROVIDER", providerId);
        assertThat(analysis.canDelete()).isFalse();
        assertThat(analysis.blockers()).containsExactlyInAnyOrder("CREDENTIAL_POOL", "PROVIDER_MODEL");
        assertThat(analysis.references()).extracting(ImpactAnalysis.Reference::name)
                .containsExactlyInAnyOrder("pool-1", "gpt-x");
        assertThat(analysis.impactVersion()).startsWith("iv-");
    }

    @Test
    void impactVersionChangesWhenReferencesChange() {
        UUID modelId = UUID.randomUUID();
        ImpactService before = new ImpactService(new StaticReferenceQuery(
                List.of(), List.of(), List.of(new ConfigReferenceQuery.EntitySummary(UUID.randomUUID(),
                        "alias-1", "ROUTE_CANDIDATE")), List.of()));
        ImpactService after = new ImpactService(new StaticReferenceQuery(
                List.of(), List.of(), List.of(new ConfigReferenceQuery.EntitySummary(UUID.randomUUID(),
                        "alias-2", "ROUTE_CANDIDATE")), List.of()));
        String first = before.analyze(connection, "PROVIDER_MODEL", modelId).impactVersion();
        String second = after.analyze(connection, "PROVIDER_MODEL", modelId).impactVersion();
        assertThat(first).isNotEqualTo(second);

        before.assertConfirmed(connection, "PROVIDER_MODEL", modelId, first);
        assertThatThrownBy(() -> after.assertConfirmed(connection, "PROVIDER_MODEL", modelId, first))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.IMPACT_ANALYSIS_EXPIRED));
    }

    @Test
    void unreferencedAliasCanDelete() {
        UUID aliasId = UUID.randomUUID();
        ImpactService service = new ImpactService(new StaticReferenceQuery(
                List.of(), List.of(), List.of(), List.of()));
        ImpactAnalysis analysis = service.analyze(connection, "MODEL_ALIAS", aliasId);
        assertThat(analysis.canDelete()).isTrue();
        assertThat(analysis.blockers()).isEmpty();
    }

    /** 静态引用源：直接给定各端口返回值。 */
    record StaticReferenceQuery(List<ConfigReferenceQuery.EntitySummary> pools,
                                List<ConfigReferenceQuery.EntitySummary> models,
                                List<ConfigReferenceQuery.EntitySummary> candidates,
                                List<ConfigReferenceQuery.EntitySummary> governance)
            implements ConfigReferenceQuery {

        @Override
        public Optional<ProviderSummary> findProviderSummary(Connection connection, UUID providerId) {
            return Optional.empty();
        }

        @Override
        public Optional<ProviderSummary> findProviderSummaryOfPool(Connection connection, UUID poolId) {
            return Optional.empty();
        }

        @Override
        public Optional<ConfigReferenceQuery.EntitySummary> findPool(Connection connection, UUID poolId) {
            return Optional.empty();
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listPoolRefsOfProvider(Connection connection, UUID providerId) {
            return pools;
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listModelRefsOfProvider(Connection connection, UUID providerId) {
            return models;
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listCredentialRefsOfPool(Connection connection, UUID poolId) {
            return List.of();
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listCandidateRefsOfModel(Connection connection, UUID modelId) {
            return candidates;
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listCandidateRefsOfPool(Connection connection, UUID poolId) {
            return candidates;
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listAliasGovernanceRefs(Connection connection, UUID aliasId) {
            return governance;
        }

        @Override
        public List<UUID> listAliasIdsReferencingModel(Connection connection, UUID modelId) {
            return candidates.isEmpty() ? List.of() : List.of(UUID.randomUUID());
        }

        @Override
        public List<UUID> listAliasIdsReferencingPool(Connection connection, UUID poolId) {
            return List.of();
        }

        @Override
        public int countAliveCredentialsOfPool(Connection connection, UUID poolId) {
            return 0;
        }

        @Override
        public Optional<UUID> findFirstAliveCredentialIdOfPool(Connection connection, UUID poolId) {
            return Optional.empty();
        }
    }
}
