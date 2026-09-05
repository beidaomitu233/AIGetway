package com.lightai.admin.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.access.AccessTestSupport.FakeAuditRepository;
import com.lightai.admin.alias.ModelAliasService;
import com.lightai.admin.alias.RouteCandidateService;
import com.lightai.admin.check.CheckInvoker;
import com.lightai.admin.check.ManagementCheckService;
import com.lightai.admin.draft.WriteContext;
import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.client.access.CandidateReorderCommand;
import com.lightai.client.access.ModelAliasCreateCommand;
import com.lightai.client.access.ModelAliasUpdateCommand;
import com.lightai.client.access.ProviderCheckCommand;
import com.lightai.client.access.RouteCandidateCreateCommand;
import com.lightai.client.access.RouteCandidateUpdateCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.storage.access.ConfigReferenceQuery;
import com.lightai.storage.access.JdbcObjectRuntimeStateRepository;
import com.lightai.storage.alias.JdbcModelAliasRepository;
import com.lightai.storage.alias.ModelAliasRepository;
import com.lightai.storage.alias.RouteCandidateRepository;
import com.lightai.storage.check.CheckRecordRepository;
import com.lightai.storage.credential.CredentialRepository;
import com.lightai.storage.credential.SecretRepository;
import com.lightai.storage.model.ProviderModelRepository;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Alias/候选语义（BE-016/017/018）：别名唯一不可变、同 Provider、重复三元组、原子重排。 */
class AliasCandidateServiceTest {

    private final DataSource dataSource = AccessTestSupport.proxyDataSource();
    private final FakeAuditRepository auditRepo = new FakeAuditRepository();
    private final AccessTestSupport.DraftFixture draft =
            AccessTestSupport.draftFixture(AccessTestSupport.auditService(auditRepo));
    private final InMemoryAliasRepository aliases = new InMemoryAliasRepository();
    private final InMemoryCandidateRepository candidates = new InMemoryCandidateRepository();
    private final InMemoryModelRepository models = new InMemoryModelRepository();
    private final StubRefQuery refs = new StubRefQuery();

    private ModelAliasService aliasService;
    private RouteCandidateService candidateService;
    private WriteContext ctx;

    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER_ID = UUID.randomUUID();
    private static final UUID POOL_ID = UUID.randomUUID();
    private static final UUID POOL_B = UUID.randomUUID();
    private static final UUID OTHER_POOL_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ManagementCheckService checkService = new ManagementCheckService(
                dataSource, AccessTestSupport.noopTransactionManager(),
                new AccessTestSupport.FakeCheckRecordRepository(), new NoSecretRepository(),
                new NoCredentialRepository(), models, candidates, refs,
                new AccessTestSupport.EmptyRuntimeStateRepository(), new YesInvoker(), record -> new byte[0],
                Clock.systemUTC());
        aliasService = new ModelAliasService(dataSource, draft.draftWriteService, aliases, candidates,
                models, draft.changes, refs, new ImpactService(refs), Clock.systemUTC());
        candidateService = new RouteCandidateService(dataSource, draft.draftWriteService, candidates,
                aliases, models, refs, checkService, Clock.systemUTC());
        ctx = new WriteContext("req-" + UUID.randomUUID(), "admin-1", "STANDALONE_SERVER", "10.0.0.1");

        models.put(PROVIDER_ID, "gpt-test");
        refs.poolProvider.put(POOL_ID, PROVIDER_ID);
        refs.poolProvider.put(POOL_B, PROVIDER_ID);
        refs.poolProvider.put(OTHER_POOL_ID, OTHER_PROVIDER_ID);
    }

    @Test
    void aliasImmutableOnUpdate() {
        var created = aliasService.create(
                new ModelAliasCreateCommand("assistant.main", "助理名", null, true), ctx).entity();
        aliasService.update(UUID.fromString(created.id()),
                new ModelAliasUpdateCommand("助理2", null, true, created.version()), ctx);
        assertThat(aliases.byId.get(UUID.fromString(created.id())).alias()).isEqualTo("assistant.main");
        assertThat(draft.changes.records.get(1).changedFields())
                .noneMatch(change -> change.fieldPath().equals("alias"));
    }

    @Test
    void duplicateAliasRejected() {
        aliasService.create(new ModelAliasCreateCommand("dup.alias", "别名一", null, true), ctx);
        assertThatThrownBy(() -> aliasService.create(new ModelAliasCreateCommand("dup.alias", "别名二", null, true), ctx))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
    }

    @Test
    void crossProviderCandidateRejected() {
        var alias = aliasService.create(new ModelAliasCreateCommand("cross.p", "跨源名", null, true), ctx).entity();
        assertThatThrownBy(() -> candidateService.create(UUID.fromString(alias.id()),
                new RouteCandidateCreateCommand(models.byModel.get("gpt-test").toString(),
                        OTHER_POOL_ID.toString(), 10, 1, true), ctx))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.OBJECT_REFERENCE_INVALID));
    }

    @Test
    void duplicateTripleRejected() {
        var alias = aliasService.create(new ModelAliasCreateCommand("dup.candidate", "重复名", null, true), ctx).entity();
        UUID modelId = models.byModel.get("gpt-test");
        candidateService.create(UUID.fromString(alias.id()),
                new RouteCandidateCreateCommand(modelId.toString(), POOL_ID.toString(), 10, 1, true), ctx);
        assertThatThrownBy(() -> candidateService.create(UUID.fromString(alias.id()),
                new RouteCandidateCreateCommand(modelId.toString(), POOL_ID.toString(), 20, 1, true), ctx))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.DUPLICATE_ROUTE_CANDIDATE));
    }

    @Test
    void reorderAtomicAndPreservesWeight() {
        var alias = aliasService.create(new ModelAliasCreateCommand("reorder.a", "重排名", null, true), ctx).entity();
        UUID aliasId = UUID.fromString(alias.id());
        UUID modelId = models.byModel.get("gpt-test");
        var first = candidateService.create(aliasId,
                new RouteCandidateCreateCommand(modelId.toString(), POOL_ID.toString(), 10, 7, true), ctx);
        var second = candidateService.create(aliasId,
                new RouteCandidateCreateCommand(modelId.toString(), POOL_B.toString(), 20, 3, true), ctx);

        List<CandidateReorderCommand.Item> items = List.of(
                new CandidateReorderCommand.Item(second.id(), 5, second.version()),
                new CandidateReorderCommand.Item(first.id(), 6, first.version()));
        var reordered = aliasService.reorder(aliasId, new CandidateReorderCommand(items), ctx);
        assertThat(reordered).extracting(detail -> detail.priority()).containsExactly(5, 6);
        assertThat(reordered).extracting(detail -> detail.weight()).containsExactly(3, 7);

        // 版本不一致 → 整批回滚（CONFIG_VERSION_CONFLICT）
        List<CandidateReorderCommand.Item> stale = List.of(
                new CandidateReorderCommand.Item(first.id(), 99, first.version() + 100),
                new CandidateReorderCommand.Item(second.id(), 98, second.version()));
        assertThatThrownBy(() -> aliasService.reorder(aliasId, new CandidateReorderCommand(stale), ctx))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFIG_VERSION_CONFLICT));
        assertThat(aliasService.candidates(aliasId))
                .extracting(detail -> detail.priority())
                .containsExactly(5, 6);
    }

    @Test
    void candidateCheckInvokesAdapterOnce() {
        var alias = aliasService.create(new ModelAliasCreateCommand("check.c", "检测名", null, true), ctx).entity();
        UUID modelId = models.byModel.get("gpt-test");
        candidateService.create(UUID.fromString(alias.id()),
                new RouteCandidateCreateCommand(modelId.toString(), POOL_ID.toString(), 10, 1, true), ctx);
        var candidate = aliasService.candidates(UUID.fromString(alias.id())).get(0);
        YesInvoker invoker = new YesInvoker();
        ManagementCheckService checkService = new ManagementCheckService(dataSource,
                AccessTestSupport.noopTransactionManager(),
                new AccessTestSupport.FakeCheckRecordRepository(), new NoSecretRepository(),
                new NoCredentialRepository(), models, candidates, refs,
                new AccessTestSupport.EmptyRuntimeStateRepository(), invoker, record -> new byte[0], Clock.systemUTC());
        RouteCandidateService service = new RouteCandidateService(dataSource, draft.draftWriteService,
                candidates, aliases, models, refs, checkService, Clock.systemUTC());
        service.check(UUID.fromString(candidate.id()),
                new ProviderCheckCommand(null, null, null, null, 1000), ctx);
        assertThat(invoker.invocations).isEqualTo(1);
    }

    static final class YesInvoker implements CheckInvoker {
        int invocations;

        @Override
        public boolean supports(String providerType) {
            return true;
        }

        @Override
        public Outcome invoke(Invocation invocation) {
            invocations++;
            return new Outcome(true, 3L, 5L, 8L, "ACTUAL", "req-1", null, null, null, null);
        }
    }

    static final class NoSecretRepository implements SecretRepository {
        @Override
        public Optional<com.lightai.storage.credential.SecretRecord> find(Connection connection, UUID credentialId) {
            return Optional.of(new com.lightai.storage.credential.SecretRecord(credentialId,
                    new byte[0], null, "k", "sk-****", 1, null, null));
        }

        @Override
        public void upsert(Connection connection, com.lightai.storage.credential.SecretRecord record) {
        }
    }

    static final class NoCredentialRepository implements CredentialRepository {
        @Override
        public Optional<com.lightai.storage.credential.CredentialRecord> find(Connection connection, UUID id) {
            return Optional.of(new com.lightai.storage.credential.CredentialRecord(id,
                    UUID.randomUUID(), "c", "INLINE_ENCRYPTED", 1, null, null, null, true, 1, null, null, null));
        }

        @Override
        public Optional<Long> findAliveVersion(Connection connection, UUID id) {
            return Optional.empty();
        }

        @Override
        public boolean existsAliveByName(Connection connection, UUID poolId, String name) {
            return false;
        }

        @Override
        public void insert(Connection connection, com.lightai.storage.credential.CredentialRecord record) {
        }

        @Override
        public void update(Connection connection, com.lightai.storage.credential.CredentialRecord record) {
        }

        @Override
        public List<CredentialRow> listByPool(Connection connection, UUID poolId, String filterSql,
                                              List<Object> filterValues, String orderSql, long offset, int limit) {
            return List.of();
        }

        @Override
        public long countByPool(Connection connection, UUID poolId, String filterSql, List<Object> filterValues) {
            return 0;
        }

        @Override
        public List<com.lightai.storage.credential.CredentialRecord> findAliveByIds(
                Connection connection, List<UUID> ids) {
            return List.of();
        }
    }

    static final class InMemoryAliasRepository implements ModelAliasRepository {
        final Map<UUID, com.lightai.storage.alias.ModelAliasRecord> byId = new java.util.HashMap<>();

        @Override
        public Optional<com.lightai.storage.alias.ModelAliasRecord> find(Connection connection, UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Long> findAliveVersion(Connection connection, UUID id) {
            var record = byId.get(id);
            return record == null ? Optional.empty() : Optional.of(record.version());
        }

        @Override
        public boolean existsAliveByAlias(Connection connection, String alias) {
            return byId.values().stream().anyMatch(record -> record.alias().equals(alias) && record.alive());
        }

        @Override
        public void insert(Connection connection, com.lightai.storage.alias.ModelAliasRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public void update(Connection connection, com.lightai.storage.alias.ModelAliasRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public List<com.lightai.storage.alias.ModelAliasRecord> list(Connection connection, String filterSql,
                                                                     List<Object> filterValues, String orderSql,
                                                                     long offset, int limit) {
            return byId.values().stream().filter(com.lightai.storage.alias.ModelAliasRecord::alive)
                    .sorted(java.util.Comparator.comparing(
                            com.lightai.storage.alias.ModelAliasRecord::alias))
                    .toList();
        }

        @Override
        public long count(Connection connection, String filterSql, List<Object> filterValues) {
            return byId.values().stream().filter(com.lightai.storage.alias.ModelAliasRecord::alive).count();
        }
    }

    static final class InMemoryCandidateRepository implements RouteCandidateRepository {
        final Map<UUID, com.lightai.storage.alias.RouteCandidateRecord> byId = new java.util.HashMap<>();

        @Override
        public Optional<com.lightai.storage.alias.RouteCandidateRecord> find(Connection connection, UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Long> findAliveVersion(Connection connection, UUID id) {
            var record = byId.get(id);
            return record == null ? Optional.empty() : Optional.of(record.version());
        }

        @Override
        public boolean existsAliveByTriple(Connection connection, UUID aliasId, UUID providerModelId,
                                           UUID credentialPoolId) {
            return byId.values().stream().anyMatch(record -> record.alive()
                    && record.aliasId().equals(aliasId)
                    && record.providerModelId().equals(providerModelId)
                    && record.credentialPoolId().equals(credentialPoolId));
        }

        @Override
        public void insert(Connection connection, com.lightai.storage.alias.RouteCandidateRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public void update(Connection connection, com.lightai.storage.alias.RouteCandidateRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public void updatePriority(Connection connection, UUID id, int priority, long newVersion) {
            var record = byId.get(id);
            byId.put(id, new com.lightai.storage.alias.RouteCandidateRecord(record.id(), record.aliasId(),
                    record.providerModelId(), record.credentialPoolId(), priority, record.weight(),
                    record.enabled(), newVersion, record.createdAt(), record.updatedAt(), record.deletedAt()));
        }

        @Override
        public List<com.lightai.storage.alias.RouteCandidateRecord> listByAlias(Connection connection,
                                                                                UUID aliasId, String orderSql) {
            return byId.values().stream()
                    .filter(record -> record.alive() && record.aliasId().equals(aliasId))
                    .sorted(java.util.Comparator.comparingInt(
                                    com.lightai.storage.alias.RouteCandidateRecord::priority)
                            .thenComparing(record -> record.id().toString()))
                    .toList();
        }

        @Override
        public List<com.lightai.storage.alias.RouteCandidateRecord> findAliveByModelIds(
                Connection connection, List<UUID> modelIds) {
            return byId.values().stream()
                    .filter(record -> record.alive() && modelIds.contains(record.providerModelId()))
                    .toList();
        }

        @Override
        public List<com.lightai.storage.alias.RouteCandidateRecord> findAliveByPoolIds(
                Connection connection, List<UUID> poolIds) {
            return List.of();
        }
    }

    static final class InMemoryModelRepository implements ProviderModelRepository {
        final Map<String, UUID> byModel = new java.util.HashMap<>();
        final Map<UUID, com.lightai.storage.model.ProviderModelRecord> byId = new java.util.HashMap<>();

        void put(UUID providerId, String modelId) {
            UUID id = UUID.randomUUID();
            byModel.put(modelId, id);
            byId.put(id, new com.lightai.storage.model.ProviderModelRecord(id, providerId, modelId,
                    modelId + "-display", "CHAT_TEXT", "O200K", 128000L, 4096L, true, true, true, true,
                    true, null, null, null, null, 4, 128, null, null, null, List.of(),
                    new java.math.BigDecimal("0.5"), new java.math.BigDecimal("1.5"), 1000000, "USD",
                    true, null, null, 1, null, null, null));
        }

        @Override
        public Optional<com.lightai.storage.model.ProviderModelRecord> find(Connection connection, UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Long> findAliveVersion(Connection connection, UUID id) {
            var record = byId.get(id);
            return record == null ? Optional.empty() : Optional.of(record.version());
        }

        @Override
        public boolean existsAliveByModelId(Connection connection, UUID providerId, String modelId) {
            return false;
        }

        @Override
        public boolean existsAliveByDisplayName(Connection connection, UUID providerId, String displayName) {
            return false;
        }

        @Override
        public void insert(Connection connection, com.lightai.storage.model.ProviderModelRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public void update(Connection connection, com.lightai.storage.model.ProviderModelRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public List<com.lightai.storage.model.ProviderModelRecord> list(Connection connection, String filterSql,
                                                                        List<Object> filterValues, String orderSql,
                                                                        long offset, int limit) {
            return byId.values().stream().filter(com.lightai.storage.model.ProviderModelRecord::alive).toList();
        }

        @Override
        public long count(Connection connection, String filterSql, List<Object> filterValues) {
            return byId.size();
        }
    }

    static final class StubRefQuery implements ConfigReferenceQuery {
        final Map<UUID, UUID> poolProvider = new java.util.HashMap<>();

        @Override
        public Optional<ConfigReferenceQuery.ProviderSummary> findProviderSummary(Connection connection, UUID providerId) {
            return Optional.of(new ConfigReferenceQuery.ProviderSummary(providerId, "provider-1", "OPENAI",
                    "https://api.openai.com/v1", true));
        }

        @Override
        public Optional<ConfigReferenceQuery.ProviderSummary> findProviderSummaryOfPool(Connection connection, UUID poolId) {
            UUID providerId = poolProvider.get(poolId);
            return providerId == null ? Optional.empty()
                    : Optional.of(new ConfigReferenceQuery.ProviderSummary(providerId, "provider-1", "OPENAI",
                    "https://api.openai.com/v1", true));
        }

        @Override
        public Optional<ConfigReferenceQuery.EntitySummary> findPool(Connection connection, UUID poolId) {
            return Optional.of(new ConfigReferenceQuery.EntitySummary(poolId, "pool-1", "CREDENTIAL_POOL"));
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listPoolRefsOfProvider(Connection connection, UUID providerId) {
            return List.of();
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listModelRefsOfProvider(Connection connection, UUID providerId) {
            return List.of();
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listCredentialRefsOfPool(Connection connection, UUID poolId) {
            return List.of();
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listCandidateRefsOfModel(Connection connection, UUID modelId) {
            return List.of();
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listCandidateRefsOfPool(Connection connection, UUID poolId) {
            return List.of();
        }

        @Override
        public List<ConfigReferenceQuery.EntitySummary> listAliasGovernanceRefs(Connection connection, UUID aliasId) {
            return List.of();
        }

        @Override
        public List<UUID> listAliasIdsReferencingModel(Connection connection, UUID modelId) {
            return List.of();
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
            return Optional.of(UUID.randomUUID());
        }
    }
}
