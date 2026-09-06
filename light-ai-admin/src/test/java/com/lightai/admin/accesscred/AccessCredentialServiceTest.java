package com.lightai.admin.accesscred;

import com.lightai.admin.security.AccessTokenService;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.security.AccessCredentialSecretResult;
import com.lightai.storage.access.AccessCredentialRecord;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Access Credential 生命周期语义（BE-044）：签发一次性、轮换失效、过期禁启、即时删除。 */
class AccessCredentialServiceTest {

    private static final String PEPPER = "unit-test-pepper";

    private FakeAccessCredentialRepository repository;
    private AccessCredentialService service;

    @BeforeEach
    void setUp() {
        repository = new FakeAccessCredentialRepository();
        var auditRepo = new com.lightai.storage.audit.AuditRepository() {
            final java.util.List<com.lightai.storage.audit.AuditRecord> records = new java.util.ArrayList<>();

            @Override
            public void insert(java.sql.Connection connection, com.lightai.storage.audit.AuditRecord record) {
                records.add(record);
            }
        };
        var auditService = new com.lightai.admin.audit.AuditService(auditRepo,
                dataSource(), noopTransactionManager(), null);
        service = new AccessCredentialService(dataSource(), repository,
                new AccessTokenService(AccessTokenService.fixedPepper(1, PEPPER)),
                () -> auditService, Clock.systemUTC(), "STANDALONE_SERVER", true);
    }

    private static javax.sql.DataSource dataSource() {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                AccessCredentialServiceTest.class.getClassLoader(),
                new Class<?>[]{javax.sql.DataSource.class},
                (proxy, method, args) -> method.getName().equals("getConnection")
                        ? java.lang.reflect.Proxy.newProxyInstance(
                                AccessCredentialServiceTest.class.getClassLoader(),
                                new Class<?>[]{java.sql.Connection.class},
                                (p, m, a) -> null)
                        : null);
    }

    private static org.springframework.transaction.PlatformTransactionManager noopTransactionManager() {
        return new org.springframework.transaction.PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
            }

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
            }
        };
    }

    private com.lightai.admin.web.RequestContext ctx() {
        com.lightai.spi.auth.AuthContext auth = com.lightai.spi.auth.AuthContext.authenticated(
                "admin-1", "管理员", java.util.Set.of("SYSTEM_ADMIN"), java.util.List.of());
        return new com.lightai.admin.web.RequestContext(auth, "req-" + UUID.randomUUID(), "10.0.0.1");
    }

    private com.lightai.client.security.AccessCredentialCreateCommand createCommand(String name) {
        return new com.lightai.client.security.AccessCredentialCreateCommand(name, "orders-app",
                java.util.List.of(), java.util.List.of("10.0.0.0/24"), null, true);
    }

    @Test
    void createReturnsTokenOnceAndStoresHashOnly() {
        AccessCredentialSecretResult result = service.create(ctx(), createCommand("app-token"));
        assertThat(result.tokenValue()).startsWith("lai_");
        assertThat(result.maskedValue()).doesNotContain(result.tokenValue());
        AccessCredentialRecord stored = repository.byId.get(UUID.fromString(result.credentialId()));
        assertThat(new String(stored.tokenHash())).doesNotContain(result.tokenValue());
        assertThat(stored.tokenHash()).hasSize(32);
        assertThat(stored.maskedValue()).isEqualTo(result.maskedValue());
        assertThat(stored.application()).isEqualTo("orders-app");
    }

    @Test
    void rotateBumpsGenerationAndInvalidatesOldToken() {
        AccessCredentialSecretResult created = service.create(ctx(), createCommand("rotate-me"));
        UUID id = UUID.fromString(created.credentialId());
        long oldGeneration = repository.byId.get(id).rotationGeneration();
        byte[] oldHash = repository.byId.get(id).tokenHash();

        AccessCredentialSecretResult rotated = service.rotate(id,
                new com.lightai.client.security.AccessCredentialRotateCommand(created.version(), "季度轮换"),
                ctx());

        assertThat(rotated.tokenValue()).isNotEqualTo(created.tokenValue());
        assertThat(repository.byId.get(id).rotationGeneration()).isEqualTo(oldGeneration + 1);
        assertThat(repository.byId.get(id).tokenHash()).isNotEqualTo(oldHash);
        // 旧 Token 摘要不再命中（旧 Token 立即失效）
        assertThat(repository.findByTokenHash(null, oldHash)).isEmpty();
        assertThat(rotated.rotationGeneration()).isEqualTo(oldGeneration + 1);
    }

    @Test
    void rotateVersionConflict() {
        AccessCredentialSecretResult created = service.create(ctx(), createCommand("rotate-conflict"));
        UUID id = UUID.fromString(created.credentialId());
        assertThatThrownBy(() -> service.rotate(id,
                new com.lightai.client.security.AccessCredentialRotateCommand(created.version() + 7, "x"), ctx()))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFIG_VERSION_CONFLICT));
    }

    @Test
    void expiredCredentialCannotReEnable() {
        com.lightai.client.security.AccessCredentialCreateCommand expiredCmd =
                new com.lightai.client.security.AccessCredentialCreateCommand("expired-cred", "orders-app",
                        java.util.List.of(), java.util.List.of(),
                        java.time.OffsetDateTime.now().minusDays(1), true);
        AccessCredentialSecretResult created = service.create(ctx(), expiredCmd);
        UUID id = UUID.fromString(created.credentialId());
        service.changeEnabled(id, false, created.version(), ctx());
        long version = repository.byId.get(id).version();
        assertThatThrownBy(() -> service.changeEnabled(id, true, version, ctx()))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCESS_CREDENTIAL_EXPIRED));
    }

    @Test
    void deleteMarksDeletedImmediately() {
        AccessCredentialSecretResult created = service.create(ctx(), createCommand("delete-me"));
        UUID id = UUID.fromString(created.credentialId());
        service.delete(id, created.version(), "下线", ctx());
        assertThat(repository.byId.get(id).alive()).isFalse();
        assertThat(repository.findByTokenHash(null, repository.byId.get(id).tokenHash())).isEmpty();
    }

    @Test
    void duplicateNameRejected() {
        service.create(ctx(), createCommand("dup-token"));
        assertThatThrownBy(() -> service.create(ctx(), createCommand("dup-token")))
                .isInstanceOfSatisfying(LightAiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED));
    }

    /** 内存凭证仓储：key 为 id。 */
    static final class FakeAccessCredentialRepository implements com.lightai.storage.access.AccessCredentialRepository {
        final java.util.Map<UUID, AccessCredentialRecord> byId = new java.util.HashMap<>();
        private final java.util.Map<UUID, java.util.List<UUID>> aliases = new java.util.HashMap<>();

        @Override
        public java.util.Optional<AccessCredentialRecord> find(java.sql.Connection connection, UUID id) {
            return java.util.Optional.ofNullable(byId.get(id));
        }

        @Override
        public java.util.Optional<AccessCredentialRecord> findByTokenHash(java.sql.Connection connection,
                                                                          byte[] tokenHash) {
            return byId.values().stream()
                    .filter(record -> record.alive()
                            && java.util.Arrays.equals(record.tokenHash(), tokenHash))
                    .findFirst();
        }

        @Override
        public boolean existsAliveByName(java.sql.Connection connection, String name) {
            return byId.values().stream().anyMatch(record -> record.alive() && record.name().equals(name));
        }

        @Override
        public void insert(java.sql.Connection connection, AccessCredentialRecord record,
                           java.util.List<UUID> aliasIds) {
            byId.put(record.id(), record);
            aliases.put(record.id(), new java.util.ArrayList<>(aliasIds));
        }

        @Override
        public void update(java.sql.Connection connection, AccessCredentialRecord record,
                           java.util.List<UUID> aliasIds) {
            byId.put(record.id(), record);
            aliases.put(record.id(), new java.util.ArrayList<>(aliasIds));
        }

        @Override
        public java.util.List<UUID> aliasIdsOf(java.sql.Connection connection, UUID credentialId) {
            return aliases.getOrDefault(credentialId, java.util.List.of());
        }

        @Override
        public java.util.List<AccessCredentialRecord> list(java.sql.Connection connection, String filterSql,
                                                           java.util.List<Object> filterValues, String orderSql,
                                                           long offset, int limit) {
            return byId.values().stream().filter(AccessCredentialRecord::alive)
                    .sorted(java.util.Comparator.comparing(AccessCredentialRecord::name))
                    .toList();
        }

        @Override
        public long count(java.sql.Connection connection, String filterSql, java.util.List<Object> filterValues) {
            return byId.values().stream().filter(AccessCredentialRecord::alive).count();
        }

        @Override
        public void touch(java.sql.Connection connection, UUID id, java.time.OffsetDateTime usedAt,
                          String maskedIp) {
            AccessCredentialRecord record = byId.get(id);
            byId.put(id, new AccessCredentialRecord(record.id(), record.name(), record.application(),
                    record.tokenPrefix(), record.tokenHash(), record.tokenHashVersion(), record.maskedValue(),
                    record.ipAllowlist(), record.expiresAt(), record.enabled(), record.rotationGeneration(),
                    record.issuedAt(), record.rotatedAt(), usedAt, maskedIp, record.version(),
                    record.createdAt(), record.updatedAt(), record.deletedAt()));
        }
    }
}
