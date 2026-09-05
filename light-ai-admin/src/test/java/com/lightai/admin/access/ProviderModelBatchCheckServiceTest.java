package com.lightai.admin.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.access.AccessTestSupport.FakeAuditRepository;
import com.lightai.admin.batch.BatchCheckService;
import com.lightai.admin.check.CheckInvoker;
import com.lightai.admin.check.ManagementCheckService;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.draft.WriteContext;
import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.model.ProviderModelService;
import com.lightai.client.access.BatchCheckCommand;
import com.lightai.client.access.ImportResult;
import com.lightai.client.access.ProviderModelCommand;
import com.lightai.client.access.ProviderModelImportCandidate;
import com.lightai.client.access.ProviderModelImportCommand;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.storage.access.ConfigReferenceQuery;
import com.lightai.storage.check.BatchCheckJobRecord;
import com.lightai.storage.check.BatchCheckJobRepository;
import com.lightai.storage.check.BatchCheckItemRecord;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.model.ProviderModelRepository;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ProviderModel 与批量检测语义（BE-014/015）：
 * 模型归属不可改、启用完整性（C-014）、逐对象导入（C-005）、批量取消与单项隔离。
 */
class ProviderModelBatchCheckServiceTest {

    private final DataSource dataSource = AccessTestSupport.proxyDataSource();
    private final FakeAuditRepository auditRepo = new FakeAuditRepository();
    private final AccessTestSupport.DraftFixture draft =
            AccessTestSupport.draftFixture(AccessTestSupport.auditService(auditRepo));
    private final InMemoryModelRepository models = new InMemoryModelRepository();
    private final AccessTestSupport.FakeCheckRecordRepository checkRecords =
            new AccessTestSupport.FakeCheckRecordRepository();
    private final StubRefQuery refs = new StubRefQuery();

    private final InMemoryBatchJobRepository batchRepo = new InMemoryBatchJobRepository();

    private ProviderModelService modelService;
    private BatchCheckService batchService;
    private WriteContext ctx;
    private static final UUID PROVIDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ManagementCheckService checkService = new ManagementCheckService(dataSource,
                AccessTestSupport.noopTransactionManager(), checkRecords, new NoSecretRepository(),
                new NoCredentialRepository(), models, new NoCandidateRepository(), refs,
                new AccessTestSupport.EmptyRuntimeStateRepository(),
                new CountingInvoker(), record -> new byte[0], Clock.systemUTC());
        modelService = new ProviderModelService(dataSource, draft.draftWriteService, models,
                new NoCandidateRepository(), draft.changes, refs,
                new AccessTestSupport.EmptyRuntimeStateRepository(), new ImpactService(refs),
                command -> List.of(), Clock.systemUTC());
        batchService = new BatchCheckService(dataSource, AccessTestSupport.noopTransactionManager(),
                batchRepo, models, refs, checkService,
                () -> record -> new byte[0], sameThreadExecutor(), Clock.systemUTC());
        ctx = new WriteContext("req-" + UUID.randomUUID(), "admin-1", "STANDALONE_SERVER", "10.0.0.1");
    }

    /** 同线程执行器：任务在提交线程内同步完成，保证测试确定性。 */
    private static ExecutorService sameThreadExecutor() {
        return new java.util.concurrent.AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
            }

            @Override
            public java.util.List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
        };
    }

    @Test
    void createValidatesCompletenessWhenEnabled() {
        // 能力缺失 + enabled=true → FIELD_VALIDATION_FAILED（C-014）
        assertThatThrownBy(() -> modelService.create(new ProviderModelCommand(
                PROVIDER_ID.toString(), "gpt-x", "GPT X", null, 2000L, 500L,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, List.of(), java.math.BigDecimal.ONE, java.math.BigDecimal.TEN,
                1000000, "USD", true, null), ctx))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
        // 同样能力缺失但 enabled=false → 允许导入草稿
        var created = modelService.create(new ProviderModelCommand(
                PROVIDER_ID.toString(), "gpt-x", "GPT X", null, 2000L, 500L,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, List.of(), java.math.BigDecimal.ONE, java.math.BigDecimal.TEN,
                1000000, "USD", false, null), ctx);
        assertThat(created.entity().enabled()).isFalse();
    }

    @Test
    void providerAndModelIdImmutableOnUpdate() {
        var created = modelService.create(new ProviderModelCommand(
                PROVIDER_ID.toString(), "gpt-fix", "GPT Fixed", "O200K", 2000L, 500L,
                true, true, true, true, true,
                new java.math.BigDecimal("0.0"), new java.math.BigDecimal("2.0"),
                new java.math.BigDecimal("0.0"), new java.math.BigDecimal("1.0"), 4, 128,
                null, null, null, List.of(), java.math.BigDecimal.ONE, java.math.BigDecimal.TEN,
                1000000, "USD", true, null), ctx).entity();
        UUID modelPk = UUID.fromString(created.id());
        assertThatThrownBy(() -> modelService.update(modelPk, new ProviderModelCommand(
                UUID.randomUUID().toString(), "gpt-other", "GPT Fixed", "O200K", 2000L, 500L,
                true, true, true, true, true, null, null, null, null, 4, 128,
                null, null, null, List.of(), java.math.BigDecimal.ONE, java.math.BigDecimal.TEN,
                1000000, "USD", true, created.version()), ctx))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFIG_FIELD_IMMUTABLE));
    }

    @Test
    void importSkipsExistingAndReportsPerObject() {
        modelService.create(new ProviderModelCommand(
                PROVIDER_ID.toString(), "gpt-exists", "GPT Exists", "O200K", 2000L, 500L,
                true, true, true, true, true, null, null, null, null, 4, 128,
                null, null, null, List.of(), java.math.BigDecimal.ONE, java.math.BigDecimal.TEN,
                1000000, "USD", false, null), ctx);

        ImportResult result = modelService.importModels(new ProviderModelImportCommand(
                PROVIDER_ID.toString(), "PROVIDER_API", null,
                List.of("gpt-exists", "gpt-new"), true, false), ctx);

        assertThat(result.created()).hasSize(1);
        assertThat(result.created().get(0).modelId()).isEqualTo("gpt-new");
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).modelId()).isEqualTo("gpt-exists");
        assertThat(result.failed()).isEmpty();
        // 逐对象事务：created 与 skipped 都产生草稿差异记录（skip 走 OBJECT_NOT_FOUND 不产生差异）
        assertThat(draft.changes.records).hasSize(2);
        assertThat(draft.state.draftRevision).isEqualTo(2);
    }

    @Test
    void batchCheckRejectsOverLimitAndMixedProviders() {
        assertThatThrownBy(() -> batchService.create(new BatchCheckCommand(
                java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(i -> UUID.randomUUID().toString()).toList(),
                null, null, null), "op", "req-1"))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
    }

    @Test
    void batchCheckRunsItemsAndFinalizesSummary() {
        for (String modelId : List.of("m-a", "m-b")) {
            modelService.create(new ProviderModelCommand(
                    PROVIDER_ID.toString(), modelId, "Model " + modelId, "O200K", 2000L, 500L,
                    true, true, true, true, true,
                    new java.math.BigDecimal("0.0"), new java.math.BigDecimal("2.0"),
                    new java.math.BigDecimal("0.0"), new java.math.BigDecimal("1.0"), 4, 128,
                    null, null, null, List.of(), java.math.BigDecimal.ONE, java.math.BigDecimal.TEN,
                    1000000, "USD", false, null), ctx);
        }
        List<String> ids = models.byModel.values().stream().map(UUID::toString).toList();
        java.util.UUID jobId = batchService.create(new BatchCheckCommand(ids, null, null, 1000),
                "admin-1", "req-batch");
        BatchCheckJobRecord job = batchRepo.jobs.get(jobId);
        assertThat(job.status()).isEqualTo(BatchCheckJobRecord.STATUS_SUCCEEDED);
        assertThat(job.completedCount()).isEqualTo(2);
        assertThat(checkRecords.records).hasSize(2);
    }

    static final class CountingInvoker implements CheckInvoker {
        int invocations;

        @Override
        public boolean supports(String providerType) {
            return true;
        }

        @Override
        public Outcome invoke(Invocation invocation) {
            invocations++;
            return new Outcome(true, 2L, 4L, 6L, "ACTUAL", "p-req", null, null, null, null);
        }
    }

    static final class NoSecretRepository implements com.lightai.storage.credential.SecretRepository {
        @Override
        public Optional<com.lightai.storage.credential.SecretRecord> find(Connection connection, UUID credentialId) {
            return Optional.of(new com.lightai.storage.credential.SecretRecord(credentialId,
                    new byte[0], null, "k", "sk-****", 1, null, null));
        }

        @Override
        public void upsert(Connection connection, com.lightai.storage.credential.SecretRecord record) {
        }
    }

    static final class NoCredentialRepository implements com.lightai.storage.credential.CredentialRepository {
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
        public List<com.lightai.storage.credential.CredentialRepository.CredentialRow> listByPool(
                Connection connection, UUID poolId, String filterSql, List<Object> filterValues,
                String orderSql, long offset, int limit) {
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

    static final class NoCandidateRepository implements com.lightai.storage.alias.RouteCandidateRepository {
        @Override
        public Optional<com.lightai.storage.alias.RouteCandidateRecord> find(Connection connection, UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> findAliveVersion(Connection connection, UUID id) {
            return Optional.empty();
        }

        @Override
        public boolean existsAliveByTriple(Connection connection, UUID aliasId, UUID providerModelId, UUID poolId) {
            return false;
        }

        @Override
        public void insert(Connection connection, com.lightai.storage.alias.RouteCandidateRecord record) {
        }

        @Override
        public void update(Connection connection, com.lightai.storage.alias.RouteCandidateRecord record) {
        }

        @Override
        public void updatePriority(Connection connection, UUID id, int priority, long newVersion) {
        }

        @Override
        public List<com.lightai.storage.alias.RouteCandidateRecord> listByAlias(Connection connection, UUID aliasId,
                                                                                String orderSql) {
            return List.of();
        }

        @Override
        public List<com.lightai.storage.alias.RouteCandidateRecord> findAliveByModelIds(
                Connection connection, List<UUID> modelIds) {
            return List.of();
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
            return byId.values().stream().anyMatch(record -> record.alive()
                    && record.providerId().equals(providerId) && record.modelId().equals(modelId));
        }

        @Override
        public boolean existsAliveByDisplayName(Connection connection, UUID providerId, String displayName) {
            return false;
        }

        @Override
        public void insert(Connection connection, com.lightai.storage.model.ProviderModelRecord record) {
            byId.put(record.id(), record);
            byModel.putIfAbsent(record.modelId(), record.id());
        }

        @Override
        public void update(Connection connection, com.lightai.storage.model.ProviderModelRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public List<com.lightai.storage.model.ProviderModelRecord> list(Connection connection, String filterSql,
                                                                        List<Object> filterValues, String orderSql,
                                                                        long offset, int limit) {
            return byId.values().stream()
                    .filter(record -> {
                        if (filterSql == null || filterValues == null) {
                            return record.alive();
                        }
                        if (filterSql.startsWith("id IN")) {
                            return filterValues.contains(record.id());
                        }
                        if (filterValues.size() != 2) {
                            return record.alive();
                        }
                        Object provider = filterValues.get(0);
                        Object second = filterValues.get(1);
                        if (!record.providerId().equals(provider)) {
                            return false;
                        }
                        if (filterSql.contains("model_id = ?")) {
                            return record.modelId().equals(second);
                        }
                        if (filterSql.contains("display_name = ?")) {
                            return record.displayName().equals(second);
                        }
                        return record.alive();
                    })
                    .sorted(java.util.Comparator.comparing(record -> record.createdAt()))
                    .toList();
        }

        @Override
        public long count(Connection connection, String filterSql, List<Object> filterValues) {
            return list(connection, filterSql, filterValues, orderSqlFor(filterSql), 0, Integer.MAX_VALUE).size();
        }

        private String orderSqlFor(String filterSql) {
            return "id asc";
        }
    }

    static final class InMemoryBatchJobRepository implements BatchCheckJobRepository {
        final Map<UUID, BatchCheckJobRecord> jobs = new java.util.HashMap<>();
        final Map<UUID, List<BatchCheckItemRecord>> items = new java.util.HashMap<>();

        @Override
        public void insert(Connection connection, BatchCheckJobRecord job, List<BatchCheckItemRecord> jobItems) {
            jobs.put(job.id(), job);
            items.put(job.id(), new java.util.ArrayList<>(jobItems));
        }

        @Override
        public Optional<BatchCheckJobRecord> find(Connection connection, UUID id) {
            return Optional.ofNullable(jobs.get(id));
        }

        @Override
        public List<BatchCheckItemRecord> listItems(Connection connection, UUID jobId) {
            return List.copyOf(items.getOrDefault(jobId, List.of()));
        }

        @Override
        public void updateSummary(Connection connection, BatchCheckJobRecord job) {
            jobs.put(job.id(), job);
        }

        @Override
        public void updateItem(Connection connection, BatchCheckItemRecord item) {
            List<BatchCheckItemRecord> list = items.get(item.jobId());
            list.replaceAll(existing -> existing.id().equals(item.id()) ? item : existing);
        }

        @Override
        public int cancelPendingItems(Connection connection, UUID jobId) {
            int cancelled = 0;
            List<BatchCheckItemRecord> list = items.get(jobId);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).status().equals(BatchCheckItemRecord.STATUS_PENDING)) {
                    list.set(i, new BatchCheckItemRecord(list.get(i).id(), jobId,
                            list.get(i).providerModelId(), list.get(i).sequence(),
                            BatchCheckItemRecord.STATUS_CANCELLED, null, null, null, null));
                    cancelled++;
                }
            }
            return cancelled;
        }
    }

    static final class StubRefQuery implements ConfigReferenceQuery {
        @Override
        public Optional<ProviderSummary> findProviderSummary(Connection connection, UUID providerId) {
            return Optional.of(new ProviderSummary(providerId, "p", "OPENAI", "https://api.openai.com/v1", true));
        }

        @Override
        public Optional<ProviderSummary> findProviderSummaryOfPool(Connection connection, UUID poolId) {
            return Optional.of(new ProviderSummary(PROVIDER_ID, "p", "OPENAI", "https://api.openai.com/v1", true));
        }

        @Override
        public Optional<EntitySummary> findPool(Connection connection, UUID poolId) {
            return Optional.of(new EntitySummary(poolId, "pool", "CREDENTIAL_POOL"));
        }

        @Override
        public List<EntitySummary> listPoolRefsOfProvider(Connection connection, UUID providerId) {
            return List.of(new EntitySummary(UUID.randomUUID(), "pool-1", "CREDENTIAL_POOL"));
        }

        @Override
        public List<EntitySummary> listModelRefsOfProvider(Connection connection, UUID providerId) {
            return List.of();
        }

        @Override
        public List<EntitySummary> listCredentialRefsOfPool(Connection connection, UUID poolId) {
            return List.of();
        }

        @Override
        public List<EntitySummary> listCandidateRefsOfModel(Connection connection, UUID modelId) {
            return List.of();
        }

        @Override
        public List<EntitySummary> listCandidateRefsOfPool(Connection connection, UUID poolId) {
            return List.of();
        }

        @Override
        public List<EntitySummary> listAliasGovernanceRefs(Connection connection, UUID aliasId) {
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
