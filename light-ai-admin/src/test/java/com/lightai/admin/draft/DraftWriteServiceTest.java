package com.lightai.admin.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.audit.AuditService;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.audit.AuditRepository;
import com.lightai.storage.draft.DraftChangeRecord;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.draft.DraftStateSnapshot;
import com.lightai.storage.draft.DraftStatus;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * BE-006 单元测试：草稿锁、乐观版本、revision 维护与事务回滚语义。
 * 使用单连接 DataSource 与支持提交/回滚的假仓储模拟真实事务边界；
 * 真实 PostgreSQL 行锁与并发行为由 DB-P01 集成测试覆盖。
 *
 * 事务语义：业务失败时业务事务以 rollback 结束（首个 rollback 前不得有 commit），
 * 失败审计以独立事务随后 begin/audit-insert/commit——这是设计行为；
 * 告警监听器仅在失败审计自身写入失败时触发（AuditService 契约）。
 */
class DraftWriteServiceTest {

    private RecordingConnection recording;
    private SingleConnectionDataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private FakeDraftStateRepository draftState;
    private FakeDraftChangeRepository draftChanges;
    private RecordingAuditRepository audits;
    private List<AuditRecord> auditWriteFailures;
    private DraftWriteService service;

    @BeforeEach
    void setUp() {
        recording = new RecordingConnection();
        dataSource = new SingleConnectionDataSource(recording.connection(), true);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionManager.afterPropertiesSet();
        draftState = new FakeDraftStateRepository(recording.calls);
        draftChanges = new FakeDraftChangeRepository(recording.calls);
        audits = new RecordingAuditRepository(recording.calls);
        auditWriteFailures = new ArrayList<>();
        AuditService auditService = new AuditService(audits, dataSource, transactionManager,
                (record, cause) -> auditWriteFailures.add(record));
        service = new DraftWriteService(dataSource, transactionManager,
                draftState, draftChanges, auditService);
        recording.onCommit = draftState::commit;
        recording.onRollback = draftState::rollback;
    }

    @Test
    void successWritesEntityDraftChangeRevisionAndAuditInSameTransaction() {
        draftChanges.existing = false;

        DraftWriteResult result = service.execute(command(1, connection -> new DraftEntityChange(
                "provider", UUID.randomUUID(), "OpenAI", "UPDATE", 2, List.of(
                FieldChange.changed("base_url", "https://a", "https://b")))));

        assertThat(result.draftRevision()).isEqualTo(6);
        assertThat(result.entityVersion()).isEqualTo(2);
        assertThat(draftState.bumpDeltas).containsExactly(1);
        assertThat(audits.inserted).hasSize(1);
        assertThat(audits.inserted.get(0).result()).isEqualTo(AuditRecord.RESULT_SUCCEEDED);
        // 锁 → 实体 → 差异 → 修订 → 审计同事务，最后统一提交
        assertThat(recording.calls).containsExactly(
                "begin", "upsert", "bump", "audit-insert", "commit");
        assertThat(recording.calls).doesNotContain("rollback");
    }

    @Test
    void staleVersionIsRejectedWithCurrentVersionWithoutWritingEntity() {
        AtomicBoolean writerInvoked = new AtomicBoolean(false);
        DraftWriteCommand command = new DraftWriteCommand(
                "req-stale", "admin", "STANDALONE_SERVER", "203.0.113.*", "UPDATE",
                "provider", "p-1", 1,
                connection -> 3L,
                connection -> {
                    writerInvoked.set(true);
                    throw new AssertionError("writer 不应执行");
                });

        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> {
                    LightAiException ex = (LightAiException) e;
                    assertThat(ex.code()).isEqualTo(ErrorCode.CONFIG_VERSION_CONFLICT);
                    assertThat(ex.currentVersion()).isEqualTo(3L);
                });

        assertThat(writerInvoked).isFalse();
        assertThat(draftState.revision).isEqualTo(5);
        assertBusinessTransactionRolledBack();
        assertIndependentFailureAudit(command.requestId(), "CONFIG_VERSION_CONFLICT");
    }

    @Test
    void missingObjectIsRejectedAsObjectNotFound() {
        DraftWriteCommand command = new DraftWriteCommand(
                "req-missing", "admin", "STANDALONE_SERVER", "203.0.113.*", "UPDATE",
                "provider", "p-404", 1,
                connection -> null,
                connection -> {
                    throw new AssertionError("writer 不应执行");
                });

        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.OBJECT_NOT_FOUND);

        assertBusinessTransactionRolledBack();
        assertIndependentFailureAudit(command.requestId(), "OBJECT_NOT_FOUND");
    }

    @Test
    void publishingStateRejectsDraftWrite() {
        draftState.status = DraftStatus.PUBLISHING;
        DraftWriteCommand command = command(1, connection -> new DraftEntityChange(
                "provider", UUID.randomUUID(), "OpenAI", "UPDATE", 2, List.of()));

        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS);

        assertBusinessTransactionRolledBack();
        assertIndependentFailureAudit(command.requestId(), "CONFIG_PUBLISH_IN_PROGRESS");
    }

    @Test
    void writerFailureRollsBackWithoutRevisionIncrease() {
        DraftWriteCommand command = command(1, connection -> {
            throw new IllegalStateException("实体写入失败");
        });

        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(IllegalStateException.class);

        assertThat(draftState.revision).isEqualTo(5);
        assertBusinessTransactionRolledBack();
        assertIndependentFailureAudit(command.requestId(), "INTERNAL_ERROR");
    }

    @Test
    void successAuditFailureRollsBackBusinessAndRaisesListener() {
        audits.failOnInsert = true;
        DraftWriteCommand command = command(1, connection -> new DraftEntityChange(
                "provider", UUID.randomUUID(), "OpenAI", "UPDATE", 2, List.of()));

        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOf(IllegalStateException.class);

        assertThat(draftState.revision).isEqualTo(5);
        assertBusinessTransactionRolledBack();
        // 审计不可写：无任何成功审计记录；失败审计自身也失败时触发告警钩子
        assertThat(audits.inserted).isEmpty();
        assertThat(auditWriteFailures).hasSize(1);
    }

    @Test
    void existingDraftChangeDoesNotDoubleCount() {
        draftChanges.existing = true;

        DraftWriteResult result = service.execute(command(1, connection -> new DraftEntityChange(
                "provider", UUID.randomUUID(), "OpenAI", "UPDATE", 2, List.of())));

        assertThat(draftState.bumpDeltas).containsExactly(0);
        assertThat(result.draftRevision()).isEqualTo(6);
    }

    @Test
    void sensitiveFieldsAreRedactedBeforeDraftChangeAndAudit() {
        draftChanges.existing = false;
        service.execute(command(1, connection -> new DraftEntityChange(
                "credential", UUID.randomUUID(), "sk-alias", "UPDATE", 2, List.of(
                FieldChange.changed("secret_ref", "vault://a", "vault://b"),
                FieldChange.changed("display_name", "旧名", "新名")))));

        assertThat(draftChanges.lastRecord).isNotNull();
        assertThat(draftChanges.lastRecord.changedFields())
                .anySatisfy(change -> {
                    assertThat(change.fieldPath()).isEqualTo("secret_ref");
                    assertThat(change.before()).isNull();
                    assertThat(change.after()).isNull();
                    assertThat(change.changed()).isTrue();
                });
        assertThat(draftChanges.lastRecord.changedFields())
                .anySatisfy(change -> {
                    assertThat(change.fieldPath()).isEqualTo("display_name");
                    assertThat(change.after()).isEqualTo("新名");
                });
        assertThat(audits.inserted.get(0).changes())
                .noneSatisfy(change -> assertThat(change.after()).isEqualTo("vault://b"));
    }

    /** 业务事务必须以回滚结束；首个 rollback 之前不得出现 commit。 */
    private void assertBusinessTransactionRolledBack() {
        assertThat(recording.calls).contains("rollback");
        int firstRollback = recording.calls.indexOf("rollback");
        assertThat(recording.calls.subList(0, firstRollback)).doesNotContain("commit");
    }

    /** 失败审计以独立事务落库，request_id 与业务可关联。 */
    private void assertIndependentFailureAudit(String requestId, String errorCode) {
        assertThat(audits.inserted).hasSize(1);
        AuditRecord failure = audits.inserted.get(0);
        assertThat(failure.result()).isEqualTo(AuditRecord.RESULT_FAILED);
        assertThat(failure.requestId()).isEqualTo(requestId);
        assertThat(failure.errorCode()).isEqualTo(errorCode);
        assertThat(recording.calls).endsWith("begin", "audit-insert", "commit");
        assertThat(auditWriteFailures).isEmpty();
    }

    private DraftWriteCommand command(long expectedVersion, DraftEntityChange.Writer writer) {
        return new DraftWriteCommand("req-" + UUID.randomUUID(), "admin", "STANDALONE_SERVER",
                "203.0.113.*", "UPDATE", "provider", "p-1", expectedVersion,
                connection -> 1L, writer);
    }

    /** 单连接事务记录器：begin/commit/rollback 顺序可见，提交/回滚钩子驱动假仓储状态。 */
    static final class RecordingConnection {
        final List<String> calls = new ArrayList<>();
        private final AtomicBoolean inTransaction = new AtomicBoolean(false);
        Runnable onCommit = () -> {
        };
        Runnable onRollback = () -> {
        };

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setAutoCommit" -> {
                                boolean autoCommit = (Boolean) args[0];
                                if (!autoCommit) {
                                    calls.add("begin");
                                    inTransaction.set(true);
                                } else {
                                    inTransaction.set(false);
                                }
                                return null;
                            }
                            case "getAutoCommit" -> {
                                return !inTransaction.get();
                            }
                            case "commit" -> {
                                calls.add("commit");
                                inTransaction.set(false);
                                onCommit.run();
                                return null;
                            }
                            case "rollback" -> {
                                calls.add("rollback");
                                inTransaction.set(false);
                                onRollback.run();
                                return null;
                            }
                            case "isClosed" -> {
                                return false;
                            }
                            case "close" -> {
                                return null;
                            }
                            default -> {
                                return method.getDefaultValue() != null ? method.getDefaultValue() : null;
                            }
                        }
                    });
        }
    }

    static final class FakeDraftStateRepository implements DraftStateRepository {
        long revision = 5;
        int changeCount = 2;
        DraftStatus status = DraftStatus.EDITABLE;
        final List<Integer> bumpDeltas = new ArrayList<>();
        private final List<String> calls;
        private int pendingDelta;

        FakeDraftStateRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Optional<DraftStateSnapshot> find(Connection connection) {
            return Optional.of(snapshot());
        }

        @Override
        public DraftStateSnapshot lock(Connection connection) {
            return snapshot();
        }

        @Override
        public DraftStateSnapshot bumpRevision(Connection connection, int changeCountDelta) {
            calls.add("bump");
            pendingDelta = changeCountDelta;
            bumpDeltas.add(changeCountDelta);
            revision++;
            changeCount += changeCountDelta;
            return snapshot();
        }

        void commit() {
            pendingDelta = 0;
        }

        void rollback() {
            if (pendingDelta != 0) {
                revision--;
                changeCount -= pendingDelta;
                bumpDeltas.remove(bumpDeltas.size() - 1);
                pendingDelta = 0;
            }
        }

        private DraftStateSnapshot snapshot() {
            return new DraftStateSnapshot(0, revision, status, null, changeCount);
        }
    }

    static final class FakeDraftChangeRepository implements DraftChangeRepository {
        boolean existing;
        DraftChangeRecord lastRecord;
        private final List<String> calls;

        FakeDraftChangeRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public boolean upsert(Connection connection, DraftChangeRecord record) {
            calls.add("upsert");
            lastRecord = record;
            return !existing;
        }

        @Override
        public boolean existsByEntity(Connection connection, String entityType, UUID entityId) {
            return existing;
        }

        @Override
        public java.util.Set<UUID> findExistingEntityIds(Connection connection, String entityType,
                                                         java.util.Collection<UUID> entityIds) {
            return existing ? new java.util.HashSet<>(entityIds) : java.util.Set.of();
        }
    }

    static final class RecordingAuditRepository implements AuditRepository {
        final List<AuditRecord> inserted = new ArrayList<>();
        boolean failOnInsert;
        private final List<String> calls;

        RecordingAuditRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void insert(Connection connection, AuditRecord record) {
            if (failOnInsert) {
                throw new IllegalStateException("审计写入失败");
            }
            calls.add("audit-insert");
            inserted.add(record);
        }
    }
}
