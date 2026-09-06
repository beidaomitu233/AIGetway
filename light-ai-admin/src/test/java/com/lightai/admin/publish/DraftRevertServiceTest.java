package com.lightai.admin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.audit.AuditService;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.ConfigDraftState;
import com.lightai.client.publish.RevertAllDraftCommand;
import com.lightai.client.publish.RevertDraftCommand;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.publish.ConfigSnapshotRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * BE-038 单元测试：单项撤销与全部撤销的事务、审计与阻塞语义。
 * 业务失败必须回滚且 revision 不增；失败审计以独立事务落库（同 request_id）。
 */
class DraftRevertServiceTest {

    private PublishTestSupport.RecordingConnection recording;
    private PublishTestSupport.FakeDraftStateRepository draftState;
    private PublishTestSupport.FakeDraftChangeQueryRepository changes;
    private PublishTestSupport.FakeSnapshotRepository snapshots;
    private PublishTestSupport.FakeSnapshotContentRepository content;
    private PublishTestSupport.FakeDependencyRepository dependencies;
    private PublishTestSupport.RecordingAuditRepository audits;
    private PublishTestSupport.FailureCollector auditFailures;
    private DraftRevertService service;

    @BeforeEach
    void setUp() {
        recording = new PublishTestSupport.RecordingConnection();
        draftState = new PublishTestSupport.FakeDraftStateRepository(recording.calls);
        changes = new PublishTestSupport.FakeDraftChangeQueryRepository(recording.calls);
        snapshots = new PublishTestSupport.FakeSnapshotRepository(recording.calls);
        content = new PublishTestSupport.FakeSnapshotContentRepository(recording.calls);
        dependencies = new PublishTestSupport.FakeDependencyRepository(recording.calls);
        audits = new PublishTestSupport.RecordingAuditRepository(recording.calls);
        auditFailures = new PublishTestSupport.FailureCollector();
        var transactionManager = new DataSourceTransactionManager(recording.dataSource());
        transactionManager.afterPropertiesSet();
        AuditService auditService = new AuditService(audits, recording.dataSource(),
                transactionManager, auditFailures);
        service = new DraftRevertService(recording.dataSource(), transactionManager,
                draftState, draftState, changes, snapshots, content, dependencies, auditService,
                "STANDALONE_SERVER");
        recording.onCommit = draftState::commit;
        recording.onRollback = draftState::rollback;
    }

    private DraftChangeFixture change(String changeType) {
        DraftChangeFixture fixture = new DraftChangeFixture("provider", UUID.randomUUID());
        changes.rows.add(new com.lightai.storage.draft.DraftChangeRow(
                UUID.randomUUID(), fixture.entityType(), fixture.entityId(), "OpenAI", changeType,
                List.of(), "admin", 2, 5, OffsetDateTime.now(), OffsetDateTime.now()));
        return fixture;
    }

    private record DraftChangeFixture(String entityType, UUID entityId) {

        String entityKey() {
            return entityType + ":" + entityId;
        }
    }

    @Test
    void revertCreateDeletesDraftObjectAndRemovesChange() {
        DraftChangeFixture fixture = change("CREATE");

        ConfigDraftState state = service.revertOne("req-1", "admin", "203.0.113.*",
                fixture.entityType(), fixture.entityId().toString(), new RevertDraftCommand(2, 5, "误操作"));

        assertThat(content.deleted).containsExactly(fixture.entityKey());
        assertThat(changes.deletes).containsExactly(fixture.entityKey());
        assertThat(draftState.bumpDeltas).containsExactly(-1);
        assertThat(state.draftRevision()).isEqualTo(6);
        assertThat(state.changeCount()).isEqualTo(1);
        assertThat(audits.inserted).hasSize(1);
        assertThat(audits.inserted.get(0).action()).isEqualTo("REVERT");
        assertThat(recording.calls).containsExactly(
                "begin", "lock-draft", "find-blockers", "delete-object", "delete-change",
                "bump", "audit-insert", "commit");
    }

    @Test
    void revertUpdateRestoresFromBaseSnapshot() {
        DraftChangeFixture fixture = change("UPDATE");
        snapshots.nextNo = 0;
        content.content.put("providers", List.of(
                Map.of("id", fixture.entityId().toString(), "name", "OpenAI", "enabled", true)));
        snapshots.snapshots.put(0L, new ConfigSnapshotRecord(0, 1,
                ConfigSnapshotRecord.STATUS_ACTIVE, content.canonical(), "checksum", "{}",
                null, "system", OffsetDateTime.now(), OffsetDateTime.now()));

        service.revertOne("req-1", "admin", "203.0.113.*",
                fixture.entityType(), fixture.entityId().toString(), new RevertDraftCommand(2, 5, "回退"));

        assertThat(content.restored).containsExactly(fixture.entityId().toString());
        assertThat(recording.calls).contains("restore-content");
    }

    @Test
    void revertBlockedWhenOtherCreateDraftReferencesTarget() {
        DraftChangeFixture fixture = change("CREATE");
        dependencies.block("provider", "候选引用 OpenAI");

        assertThatThrownBy(() -> service.revertOne("req-1", "admin", "203.0.113.*",
                fixture.entityType(), fixture.entityId().toString(), new RevertDraftCommand(2, 5, "撤销")))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.DRAFT_REVERT_BLOCKED);

        assertThat(content.deleted).isEmpty();
        assertRolledBackWithFailureAudit("req-1", "DRAFT_REVERT_BLOCKED");
    }

    @Test
    void staleObjectVersionIsRejectedWithCurrentVersion() {
        DraftChangeFixture fixture = change("UPDATE");

        assertThatThrownBy(() -> service.revertOne("req-1", "admin", "203.0.113.*",
                fixture.entityType(), fixture.entityId().toString(), new RevertDraftCommand(1, 5, "撤销")))
                .isInstanceOf(LightAiException.class)
                .satisfies(e -> {
                    LightAiException ex = (LightAiException) e;
                    assertThat(ex.code()).isEqualTo(ErrorCode.CONFIG_VERSION_CONFLICT);
                    assertThat(ex.currentVersion()).isEqualTo(2L);
                });

        assertRolledBackWithFailureAudit("req-1", "CONFIG_VERSION_CONFLICT");
    }

    @Test
    void revisionMismatchIsRejectedAsDraftChanged() {
        DraftChangeFixture fixture = change("UPDATE");

        assertThatThrownBy(() -> service.revertOne("req-1", "admin", "203.0.113.*",
                fixture.entityType(), fixture.entityId().toString(), new RevertDraftCommand(2, 4, "撤销")))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONFIG_DRAFT_CHANGED);

        assertRolledBackWithFailureAudit("req-1", "CONFIG_DRAFT_CHANGED");
    }

    @Test
    void publishingStateRejectsRevert() {
        DraftChangeFixture fixture = change("UPDATE");
        draftState.status = com.lightai.storage.draft.DraftStatus.PUBLISHING;

        assertThatThrownBy(() -> service.revertOne("req-1", "admin", "203.0.113.*",
                fixture.entityType(), fixture.entityId().toString(), new RevertDraftCommand(2, 5, "撤销")))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS);

        assertRolledBackWithFailureAudit("req-1", "CONFIG_PUBLISH_IN_PROGRESS");
    }

    @Test
    void unknownEntityTypeIsFieldValidationFailure() {
        assertThatThrownBy(() -> service.revertOne("req-1", "admin", "203.0.113.*",
                "runtime_config", UUID.randomUUID().toString(), new RevertDraftCommand(2, 5, "撤销")))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
    }

    @Test
    void revertAllRestoresBaselineAndClearsChangesAtomically() {
        snapshots.nextNo = 0;
        content.content.put("providers", List.of(
                Map.of("id", UUID.randomUUID().toString(), "name", "OpenAI", "enabled", true)));
        snapshots.snapshots.put(0L, new ConfigSnapshotRecord(0, 1,
                ConfigSnapshotRecord.STATUS_ACTIVE, content.canonical(), "checksum", "{}",
                null, "system", OffsetDateTime.now(), OffsetDateTime.now()));
        change("CREATE");
        change("UPDATE");

        ConfigDraftState state = service.revertAll("req-9", "admin", "203.0.113.*",
                new RevertAllDraftCommand(5, "REVERT ALL", "全部回退"));

        assertThat(recording.calls).contains("restore-content", "delete-object",
                "delete-all-changes", "activate-baseline");
        // 逐对象审计 + 汇总审计
        assertThat(audits.inserted).hasSize(3);
        assertThat(audits.inserted.stream().map(AuditRecord::action))
                .containsExactly("REVERT", "REVERT", "REVERT_ALL");
        assertThat(state.changeCount()).isZero();
        assertThat(state.draftRevision()).isEqualTo(6);
        assertThat(recording.calls).doesNotContain("rollback");
    }

    @Test
    void revertAllWithoutBaselineRollsBackCompletely() {
        content.failRestore = false;
        snapshots.nextNo = 0;
        // 无基线快照行：CONFIG_DATA_UNAVAILABLE
        change("UPDATE");

        assertThatThrownBy(() -> service.revertAll("req-9", "admin", "203.0.113.*",
                new RevertAllDraftCommand(5, "REVERT ALL", "全部回退")))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONFIG_DATA_UNAVAILABLE);

        assertRolledBackWithFailureAudit("req-9", "CONFIG_DATA_UNAVAILABLE");
    }

    @Test
    void revertAllWithRestoreFailureRollsBackAndWritesFailureAudit() {
        snapshots.nextNo = 0;
        content.content.put("providers", List.of(
                Map.of("id", UUID.randomUUID().toString(), "name", "OpenAI")));
        snapshots.snapshots.put(0L, new ConfigSnapshotRecord(0, 1,
                ConfigSnapshotRecord.STATUS_ACTIVE, content.canonical(), "checksum", "{}",
                null, "system", OffsetDateTime.now(), OffsetDateTime.now()));
        change("CREATE");
        content.failRestore = true;

        assertThatThrownBy(() -> service.revertAll("req-9", "admin", "203.0.113.*",
                new RevertAllDraftCommand(5, "REVERT ALL", "全部回退")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(draftState.revision).isEqualTo(5);
        assertRolledBackWithFailureAudit("req-9", "INTERNAL_ERROR");
    }

    private void assertRolledBackWithFailureAudit(String requestId, String errorCode) {
        assertThat(recording.calls).contains("rollback");
        int firstRollback = recording.calls.indexOf("rollback");
        assertThat(recording.calls.subList(0, firstRollback)).doesNotContain("commit");
        assertThat(audits.inserted).hasSize(1);
        AuditRecord failure = audits.inserted.get(0);
        assertThat(failure.result()).isEqualTo(AuditRecord.RESULT_FAILED);
        assertThat(failure.requestId()).isEqualTo(requestId);
        assertThat(failure.errorCode()).isEqualTo(errorCode);
        assertThat(auditFailures.failures).isEmpty();
    }
}
