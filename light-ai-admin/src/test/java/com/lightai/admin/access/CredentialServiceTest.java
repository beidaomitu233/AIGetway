package com.lightai.admin.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.access.AccessTestSupport.DraftFixture;
import com.lightai.admin.access.AccessTestSupport.FakeAuditRepository;
import com.lightai.admin.credential.CredentialService;
import com.lightai.admin.draft.WriteContext;
import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.secret.SecretCipher;
import com.lightai.admin.secret.SecretManager;
import com.lightai.client.access.CredentialCreateCommand;
import com.lightai.client.access.CredentialDetail;
import com.lightai.client.access.CredentialListItem;
import com.lightai.client.access.CredentialRotateCommand;
import com.lightai.client.access.CredentialUpdateCommand;
import com.lightai.client.access.ImpactAnalysis;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.management.ManagementOperationResult;
import com.lightai.client.paging.PageResult;
import com.lightai.storage.access.ConfigReferenceQuery;
import com.lightai.storage.access.JdbcConfigReferenceQuery;
import com.lightai.storage.access.JdbcObjectRuntimeStateRepository;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.credential.CredentialRecord;
import com.lightai.storage.credential.CredentialRepository;
import com.lightai.storage.credential.SecretRecord;
import com.lightai.storage.credential.SecretRepository;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CredentialService 语义（BE-013）：草稿事务、名称冲突、轮换、删除占用与审计关联。 */
class CredentialServiceTest {

    private static final UUID POOL_ID = UUID.randomUUID();

    private FakeAuditRepository auditRepo;
    private DraftFixture draft;
    private FakeCredentialRepository credentials;
    private FakeSecretRepository secrets;
    private CredentialService service;
    private WriteContext ctx;

    @BeforeEach
    void setUp() {
        auditRepo = new FakeAuditRepository();
        draft = AccessTestSupport.draftFixture(AccessTestSupport.auditService(auditRepo));
        credentials = new FakeCredentialRepository();
        secrets = new FakeSecretRepository();
        ConfigReferenceQuery referenceQuery = new StubReferenceQuery();
        SecretManager secretManager = new SecretManager(new SecretCipher(
                SecretCipher.fixedKeyProvider("k1", SecretCipher.randomBase64Key().get())));
        service = new CredentialService(AccessTestSupport.proxyDataSource(),
                AccessTestSupport.noopTransactionManager(), draft.draftWriteService, credentials, secrets,
                draft.changes, new com.lightai.storage.check.JdbcCheckRecordRepository(), referenceQuery,
                new AccessTestSupport.EmptyRuntimeStateRepository(), secretManager,
                AccessTestSupport.auditService(auditRepo), new ImpactService(referenceQuery),
                credentialId -> 0, Clock.systemUTC());
        ctx = new WriteContext("req-" + UUID.randomUUID(), "admin-1", "STANDALONE_SERVER", "10.0.0.1");
    }

    @Test
    void createInlineWritesDraftRevisionAndAudit() {
        ManagementOperationResult<CredentialDetail> result = service.create(POOL_ID,
                new CredentialCreateCommand("primary-key", "INLINE_ENCRYPTED", "sk-value-1", "sk-value-1",
                        null, 5, 100L, 2000L, 10, true), ctx);

        assertThat(result.draftChanged()).isTrue();
        assertThat(result.draftRevision()).isEqualTo(1);
        assertThat(result.entity().maskedValue()).isNotBlank();
        assertThat(result.entity().healthStatus()).isEqualTo("UNKNOWN");
        assertThat(draft.state.draftRevision).isEqualTo(1);
        assertThat(draft.changes.records).hasSize(1);
        // 审计包含敏感字段占位（仅 field_path + changed），不落值
        assertThat(auditRepo.records).hasSize(1);
        assertThat(auditRepo.records.get(0).result()).isEqualTo("SUCCEEDED");
        assertThat(String.valueOf(auditRepo.records.get(0).changes())).doesNotContain("sk-value-1");
    }

    @Test
    void duplicateNameInPoolRejected() {
        service.create(POOL_ID, new CredentialCreateCommand("dup-key", "INLINE_ENCRYPTED",
                "v1", "v1", null, null, null, null, null, true), ctx);
        assertThatThrownBy(() -> service.create(POOL_ID, new CredentialCreateCommand("dup-key",
                        "INLINE_ENCRYPTED", "v2", "v2", null, null, null, null, null, true), ctx))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
        assertThat(draft.state.draftRevision).isEqualTo(1);
    }

    @Test
    void updateDoesNotTouchSecret() {
        CredentialDetail created = service.create(POOL_ID, new CredentialCreateCommand("cred-a",
                "INLINE_ENCRYPTED", "sk-v", "sk-v", null, null, null, null, null, true), ctx).entity();
        long beforeVersion = secrets.byId.get(UUID.fromString(created.id())).secretVersion();
        service.update(UUID.fromString(created.id()),
                new CredentialUpdateCommand("cred-a2", null, 9, 50L, null, null, true, created.version()), ctx);
        assertThat(secrets.byId.get(UUID.fromString(created.id())).secretVersion()).isEqualTo(beforeVersion);
        assertThat(credentials.byId.get(UUID.fromString(created.id())).weight()).isEqualTo(9);
    }

    @Test
    void rotateBumpsSecretVersionWithoutDraftChange() {
        CredentialDetail created = service.create(POOL_ID, new CredentialCreateCommand("cred-r",
                "INLINE_ENCRYPTED", "sk-old", "sk-old", null, null, null, null, null, true), ctx).entity();
        int auditCount = auditRepo.records.size();

        ManagementOperationResult<CredentialDetail> rotated = service.rotate(UUID.fromString(created.id()),
                new CredentialRotateCommand("sk-new", "sk-new", created.version()), ctx);

        SecretRecord secret = secrets.byId.get(UUID.fromString(created.id()));
        assertThat(secret.secretVersion()).isEqualTo(2);
        assertThat(secret.maskedValue()).isNotEqualTo("sk-old");
        assertThat(rotated.draftRevision()).isNull();
        assertThat(draft.changes.records).hasSize(1);
        assertThat(auditRepo.records).hasSize(auditCount + 1);
        assertThat(auditRepo.records.get(auditRepo.records.size() - 1).action()).isEqualTo("ROTATE");
    }

    @Test
    void rotateVersionConflict() {
        CredentialDetail created = service.create(POOL_ID, new CredentialCreateCommand("cred-c",
                "INLINE_ENCRYPTED", "sk-x", "sk-x", null, null, null, null, null, true), ctx).entity();
        assertThatThrownBy(() -> service.rotate(UUID.fromString(created.id()),
                new CredentialRotateCommand("sk-y", "sk-y", created.version() + 5), ctx))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFIG_VERSION_CONFLICT));
    }

    @Test
    void deleteBlockedByRunningAttempts() {
        CredentialDetail created = service.create(POOL_ID, new CredentialCreateCommand("cred-d",
                "INLINE_ENCRYPTED", "sk-z", "sk-z", null, null, null, null, null, true), ctx).entity();
        CredentialService busy = new CredentialService(AccessTestSupport.proxyDataSource(),
                AccessTestSupport.noopTransactionManager(), draft.draftWriteService, credentials, secrets,
                draft.changes, new com.lightai.storage.check.JdbcCheckRecordRepository(),
                new StubReferenceQuery(), new AccessTestSupport.EmptyRuntimeStateRepository(),
                new SecretManager(new SecretCipher(SecretCipher.fixedKeyProvider("k1",
                        SecretCipher.randomBase64Key().get()))),
                AccessTestSupport.auditService(auditRepo), new ImpactService(new StubReferenceQuery()),
                credentialId -> 2, Clock.systemUTC());
        assertThatThrownBy(() -> busy.delete(UUID.fromString(created.id()), created.version(), ctx))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CAPACITY_IN_USE));
    }

    @Test
    void listMasksSecretsAndReportsDraftChanged() {
        service.create(POOL_ID, new CredentialCreateCommand("cred-l1", "INLINE_ENCRYPTED",
                "sk-list", "sk-list", null, null, null, null, null, true), ctx);
        PageResult<CredentialListItem> page = service.list(POOL_ID, null, true,
                ListQuerySupport.parse(null, null, null, CredentialService.SORTABLE, "name asc"));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).maskedValue()).doesNotContain("sk-list");
        assertThat(page.items().get(0).draftChanged()).isTrue();
    }

    /** 内存凭证仓储：key 为 id；version 语义由服务维护。 */
    static final class FakeCredentialRepository implements CredentialRepository {
        final Map<UUID, CredentialRecord> byId = new HashMap<>();

        @Override
        public Optional<CredentialRecord> find(Connection connection, UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Long> findAliveVersion(Connection connection, UUID id) {
            CredentialRecord record = byId.get(id);
            return record == null ? Optional.empty() : Optional.of(record.version());
        }

        @Override
        public boolean existsAliveByName(Connection connection, UUID poolId, String name) {
            return byId.values().stream().anyMatch(record -> record.poolId().equals(poolId)
                    && record.name().equals(name) && record.alive());
        }

        @Override
        public void insert(Connection connection, CredentialRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public void update(Connection connection, CredentialRecord record) {
            byId.put(record.id(), record);
        }

        @Override
        public List<CredentialRow> listByPool(Connection connection, UUID poolId, String filterSql,
                                              List<Object> filterValues, String orderSql, long offset, int limit) {
            List<CredentialRow> rows = new ArrayList<>();
            byId.values().stream()
                    .filter(record -> record.poolId().equals(poolId) && record.alive())
                    .sorted(java.util.Comparator.comparing(CredentialRecord::name))
                    .forEach(record -> rows.add(new CredentialRow(record, "sk-****" + record.name())));
            return rows;
        }

        @Override
        public long countByPool(Connection connection, UUID poolId, String filterSql, List<Object> filterValues) {
            return byId.values().stream().filter(record -> record.poolId().equals(poolId) && record.alive()).count();
        }

        @Override
        public List<CredentialRecord> findAliveByIds(Connection connection, List<UUID> ids) {
            return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        }
    }

    static final class FakeSecretRepository implements SecretRepository {
        final Map<UUID, SecretRecord> byId = new HashMap<>();

        @Override
        public Optional<SecretRecord> find(Connection connection, UUID credentialId) {
            return Optional.ofNullable(byId.get(credentialId));
        }

        @Override
        public void upsert(Connection connection, SecretRecord record) {
            byId.put(record.credentialId(), record);
        }
    }

    /** 引用查询桩：池存在、无引用；影响分析断言在 ImpactServiceTest 细化。 */
    static final class StubReferenceQuery implements ConfigReferenceQuery {
        @Override
        public Optional<ProviderSummary> findProviderSummary(Connection connection, UUID providerId) {
            return Optional.of(new ProviderSummary(providerId, "p", "OPENAI", "https://api.openai.com/v1", true));
        }

        @Override
        public Optional<ProviderSummary> findProviderSummaryOfPool(Connection connection, UUID poolId) {
            return Optional.of(new ProviderSummary(poolId, "p", "OPENAI", "https://api.openai.com/v1", true));
        }

        @Override
        public Optional<com.lightai.storage.access.ConfigReferenceQuery.EntitySummary> findPool(
                Connection connection, UUID poolId) {
            return Optional.of(new com.lightai.storage.access.ConfigReferenceQuery.EntitySummary(
                    poolId, "pool-" + poolId.toString().substring(0, 8), "CREDENTIAL_POOL"));
        }

        @Override
        public List<com.lightai.storage.access.ConfigReferenceQuery.EntitySummary> listPoolRefsOfProvider(
                Connection connection, UUID providerId) {
            return List.of();
        }

        @Override
        public List<com.lightai.storage.access.ConfigReferenceQuery.EntitySummary> listModelRefsOfProvider(
                Connection connection, UUID providerId) {
            return List.of();
        }

        @Override
        public List<com.lightai.storage.access.ConfigReferenceQuery.EntitySummary> listCredentialRefsOfPool(
                Connection connection, UUID poolId) {
            return List.of();
        }

        @Override
        public List<com.lightai.storage.access.ConfigReferenceQuery.EntitySummary> listCandidateRefsOfModel(
                Connection connection, UUID modelId) {
            return List.of();
        }

        @Override
        public List<com.lightai.storage.access.ConfigReferenceQuery.EntitySummary> listCandidateRefsOfPool(
                Connection connection, UUID poolId) {
            return List.of();
        }

        @Override
        public List<com.lightai.storage.access.ConfigReferenceQuery.EntitySummary> listAliasGovernanceRefs(
                Connection connection, UUID aliasId) {
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
            return Optional.empty();
        }
    }
}
