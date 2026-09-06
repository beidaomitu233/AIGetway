package com.lightai.admin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.AdminProperties;
import com.lightai.admin.audit.AuditService;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.ConfigPublishCommand;
import com.lightai.client.publish.InstanceLoadReport;
import com.lightai.client.publish.PublishRecordDetailView;
import com.lightai.client.publish.PublishRecordListItemView;
import com.lightai.client.publish.PublishInstanceResultView;
import com.lightai.client.publish.RuntimeHeartbeatResponse;
import com.lightai.client.publish.RuntimeInstanceHeartbeat;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.publish.ConfigSnapshotRecord;
import com.lightai.storage.publish.ConfigValidationIssueRecord;
import com.lightai.storage.publish.ConfigValidationRecord;
import com.lightai.storage.publish.PublishInstanceResultRecord;
import com.lightai.storage.publish.PublishRecordRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * BE-040/041/042 单元测试：发布两阶段、实例上报与收敛、超时恢复。
 * 非法命令不创建 PublishRecord（C-007）；激活只有一个 ACTIVE。
 */
class ConfigPublishServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T02:00:00Z"),
            ZoneOffset.UTC);

    private PublishTestSupport.RecordingConnection recording;
    private PublishTestSupport.FakeDraftStateRepository draftState;
    private PublishTestSupport.FakeDraftChangeQueryRepository changes;
    private PublishTestSupport.FakeSnapshotRepository snapshots;
    private PublishTestSupport.FakeSnapshotContentRepository content;
    private PublishTestSupport.FakeValidationRepository validations;
    private PublishTestSupport.FakePublishRecordRepository publishes;
    private PublishTestSupport.FakeInstanceResultRepository instanceResults;
    private PublishTestSupport.FakeRuntimeInstanceRepository instances;
    private PublishTestSupport.RecordingAuditRepository audits;
    private AdminProperties properties;
    private ConfigPublishService service;
    private UUID instanceId;

    @BeforeEach
    void setUp() {
        recording = new PublishTestSupport.RecordingConnection();
        draftState = new PublishTestSupport.FakeDraftStateRepository(recording.calls);
        changes = new PublishTestSupport.FakeDraftChangeQueryRepository(recording.calls);
        snapshots = new PublishTestSupport.FakeSnapshotRepository(recording.calls);
        content = new PublishTestSupport.FakeSnapshotContentRepository(recording.calls);
        content.content = ConfigValidationServiceTest.validContent();
        validations = new PublishTestSupport.FakeValidationRepository(recording.calls);
        publishes = new PublishTestSupport.FakePublishRecordRepository(recording.calls);
        instanceResults = new PublishTestSupport.FakeInstanceResultRepository(recording.calls);
        instances = new PublishTestSupport.FakeRuntimeInstanceRepository(recording.calls);
        audits = new PublishTestSupport.RecordingAuditRepository(recording.calls);
        properties = new AdminProperties();
        properties.setRuntimeMode("STANDALONE_SERVER");
        var transactionManager = new DataSourceTransactionManager(recording.dataSource());
        transactionManager.afterPropertiesSet();
        AuditService auditService = new AuditService(audits, recording.dataSource(),
                transactionManager, new PublishTestSupport.FailureCollector());
        service = new ConfigPublishService(recording.dataSource(), transactionManager, CLOCK,
                draftState, draftState, changes, snapshots, content, validations, publishes,
                instanceResults, instances, auditService, properties);
        recording.onCommit = draftState::commit;
        recording.onRollback = draftState::rollback;
        instanceId = UUID.randomUUID();
        instances.online(instanceId);
    }

    private ConfigValidationRecord seedValidation() {
        String checksum = ConfigValidationService.sha256Hex(
                content.canonicalJson(content.assemble(null, properties.getTimezone())));
        UUID validationId = UUID.randomUUID();
        validations.lastRecord = new ConfigValidationRecord(validationId, 0, 1, 5, checksum,
                ConfigValidationRecord.STATUS_PASSED, 0, 0,
                OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK).plusMinutes(10), "admin",
                null, "[]", List.of(), "[]");
        return validations.lastRecord;
    }

    @Test
    void publishCreatesPreparationAndLocksDraft() {
        ConfigValidationRecord validation = seedValidation();

        PublishRecordDetailView detail = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5,
                        List.of(), "首发"));

        assertThat(detail.status()).isEqualTo(PublishRecordRecord.STATUS_PREPARING);
        assertThat(detail.targetSnapshotNo()).isEqualTo(1);
        assertThat(detail.instanceResults()).hasSize(1);
        assertThat(detail.instanceResults().get(0).status())
                .isEqualTo(PublishInstanceResultRecord.STATUS_PENDING);
        assertThat(detail.contentChecksum()).isEqualTo(validation.contentChecksum());
        ConfigSnapshotRecord snapshot = snapshots.snapshots.get(1L);
        assertThat(snapshot.status()).isEqualTo(ConfigSnapshotRecord.STATUS_CREATED);
        assertThat(snapshot.contentChecksum()).isEqualTo(detail.contentChecksum());
        assertThat(draftState.status).isEqualTo(com.lightai.storage.draft.DraftStatus.PUBLISHING);
        assertThat(validations.used).contains(validation.validationId());
        assertThat(audits.inserted).hasSize(1);
        assertThat(recording.calls).endsWith("audit-insert", "commit");
    }

    @Test
    void publishWithoutOnlineInstanceIsRejectedWithoutRecord() {
        seedValidation();
        instances.instances.clear();

        assertThatThrownBy(() -> service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validations.lastRecord.validationId().toString(), 5,
                        List.of(), null)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.NO_ONLINE_RUNTIME_INSTANCE);

        assertThat(publishes.records).isEmpty();
        assertThat(snapshots.snapshots).isEmpty();
        assertThat(draftState.status).isEqualTo(com.lightai.storage.draft.DraftStatus.EDITABLE);
        assertFailureAudit("req-1", "NO_ONLINE_RUNTIME_INSTANCE");
    }

    @Test
    void expiredOrUsedValidationIsRejected() {
        ConfigValidationRecord validation = seedValidation();
        validations.lastRecord = shiftExpiry(validation, OffsetDateTime.now(CLOCK).minusSeconds(1));

        assertThatThrownBy(() -> service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONFIG_VALIDATION_EXPIRED);

        ConfigValidationRecord reusable = seedValidation();
        validations.markUsed(null, reusable.validationId(), UUID.randomUUID());
        assertThatThrownBy(() -> service.publish("req-2", "admin", "203.0.113.*",
                new ConfigPublishCommand(reusable.validationId().toString(), 5, List.of(), null)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONFIG_VALIDATION_EXPIRED);
        assertThat(publishes.records).isEmpty();
    }

    @Test
    void revisionMismatchWithValidationIsRejected() {
        ConfigValidationRecord validation = seedValidation();

        assertThatThrownBy(() -> service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 4, List.of(), null)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONFIG_DRAFT_CHANGED);
        assertThat(publishes.records).isEmpty();
    }

    @Test
    void draftChangedAfterValidationIsRejectedByChecksum() {
        ConfigValidationRecord validation = seedValidation();
        content.content.put("providers", List.of()); // 校验后草稿内容变化

        assertThatThrownBy(() -> service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONFIG_DRAFT_CHANGED);
        assertThat(publishes.records).isEmpty();
    }

    @Test
    void missingWarningAcknowledgementIsRejected() {
        ConfigValidationRecord validation = seedValidation();
        validations.lastIssues.add(new ConfigValidationIssueRecord(validation.validationId(),
                ConfigValidationIssueViewSeverity(), "CONNECTION_CHECK_STALE", "credential",
                UUID.randomUUID(), "sk-***", null, "凭证最近 24 小时无成功检测记录",
                "发布前执行一次凭证检测", List.of()));
        validations.lastRecord = withCounts(validation, 0, 1);

        assertThatThrownBy(() -> service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
        assertThat(publishes.records).isEmpty();

        // 全部确认后通过
        PublishRecordDetailView detail = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5,
                        List.of("CONNECTION_CHECK_STALE"), null));
        assertThat(detail.status()).isEqualTo(PublishRecordRecord.STATUS_PREPARING);
    }

    @Test
    void allReadyReportsTriggerAtomicActivation() {
        ConfigValidationRecord validation = seedValidation();
        PublishRecordDetailView prepared = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null));

        PublishInstanceResultView result = service.applyReport(
                UUID.fromString(prepared.id()), instanceId,
                new InstanceLoadReport(1, PublishInstanceResultRecord.STATUS_READY,
                        OffsetDateTime.now(CLOCK), 0, 120L, null, null));

        assertThat(result.status()).isEqualTo(PublishInstanceResultRecord.STATUS_ACTIVATING);
        assertThat(snapshots.snapshots.get(1L).status()).isEqualTo(ConfigSnapshotRecord.STATUS_ACTIVE);
        assertThat(draftState.baseSnapshotNo).isEqualTo(1);
        assertThat(draftState.status).isEqualTo(com.lightai.storage.draft.DraftStatus.EDITABLE);
        assertThat(draftState.changeCount).isZero();
        assertThat(changes.deletedAll).isEqualTo(0); // 草稿本无差异行
        assertThat(publishes.records.get(UUID.fromString(prepared.id())).status())
                .isEqualTo(PublishRecordRecord.STATUS_ACTIVATING);
        assertThat(recording.calls).contains("activate-snapshot", "activate-baseline");
    }

    @Test
    void allLoadedReportsConvergePublishToSucceeded() {
        ConfigValidationRecord validation = seedValidation();
        PublishRecordDetailView prepared = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null));
        UUID publishId = UUID.fromString(prepared.id());
        service.applyReport(publishId, instanceId, new InstanceLoadReport(1,
                PublishInstanceResultRecord.STATUS_READY, OffsetDateTime.now(CLOCK), 0, 100L, null, null));

        PublishInstanceResultView loaded = service.applyReport(publishId, instanceId,
                new InstanceLoadReport(1, PublishInstanceResultRecord.STATUS_LOADED,
                        OffsetDateTime.now(CLOCK), 0, 300L, null, null));

        assertThat(loaded.status()).isEqualTo(PublishInstanceResultRecord.STATUS_LOADED);
        PublishRecordRecord record = publishes.records.get(publishId);
        assertThat(record.status()).isEqualTo(PublishRecordRecord.STATUS_SUCCEEDED);
        assertThat(record.completedAt()).isNotNull();
        assertThat(record.convergedAt()).isNotNull();
        assertThat(record.durationMs()).isNotNull();
    }

    @Test
    void staleReportIsRejectedWithConflict() {
        ConfigValidationRecord validation = seedValidation();
        PublishRecordDetailView prepared = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null));

        assertThatThrownBy(() -> service.applyReport(UUID.fromString(prepared.id()), instanceId,
                new InstanceLoadReport(1, PublishInstanceResultRecord.STATUS_LOADED,
                        OffsetDateTime.now(CLOCK), 0, null, null, null)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.INSTANCE_REPORT_CONFLICT);

        // 旧 reported_at 被拒绝
        service.applyReport(UUID.fromString(prepared.id()), instanceId, new InstanceLoadReport(1,
                PublishInstanceResultRecord.STATUS_READY, OffsetDateTime.now(CLOCK), 0, null, null, null));
        assertThatThrownBy(() -> service.applyReport(UUID.fromString(prepared.id()), instanceId,
                new InstanceLoadReport(1, PublishInstanceResultRecord.STATUS_READY,
                        OffsetDateTime.now(CLOCK).minusSeconds(60), 0, null, null, null)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.INSTANCE_REPORT_CONFLICT);
    }

    @Test
    void heartbeatIssuesPrepareCommandForPendingInstance() {
        ConfigValidationRecord validation = seedValidation();
        PublishRecordDetailView prepared = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null));

        RuntimeHeartbeatResponse response = service.heartbeat(new RuntimeInstanceHeartbeat(
                instanceId.toString(), "STANDALONE_SERVER", "1.0.0-test", "app", null,
                List.of("1"), List.of("OPENAI"), 0, true, OffsetDateTime.now(CLOCK)));

        assertThat(response.prepareCommand()).isNotNull();
        assertThat(response.prepareCommand().publishId()).isEqualTo(prepared.id());
        assertThat(response.prepareCommand().snapshotNo()).isEqualTo(1);
        assertThat(response.activationCommand()).isNull();
        assertThat(instanceResults.find(null, UUID.fromString(prepared.id()), instanceId)
                .orElseThrow().status()).isEqualTo(PublishInstanceResultRecord.STATUS_PREPARING);
    }

    @Test
    void heartbeatIssuesActivationCommandWhenActivating() {
        ConfigValidationRecord validation = seedValidation();
        PublishRecordDetailView prepared = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null));
        UUID publishId = UUID.fromString(prepared.id());
        service.applyReport(publishId, instanceId, new InstanceLoadReport(1,
                PublishInstanceResultRecord.STATUS_READY, OffsetDateTime.now(CLOCK), 0, null, null, null));

        RuntimeHeartbeatResponse response = service.heartbeat(new RuntimeInstanceHeartbeat(
                instanceId.toString(), "STANDALONE_SERVER", "1.0.0-test", "app", null,
                List.of("1"), List.of("OPENAI"), 1, true, OffsetDateTime.now(CLOCK)));

        assertThat(response.prepareCommand()).isNull();
        assertThat(response.activationCommand()).isNotNull();
        assertThat(response.activationCommand().publishId()).isEqualTo(publishId.toString());
        assertThat(response.activeSnapshotNo()).isEqualTo(1);
    }

    @Test
    void prepareTimeoutAbortsSnapshotFailsPublishAndReleasesDraft() {
        ConfigValidationRecord validation = seedValidation();
        PublishRecordDetailView prepared = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null));
        UUID publishId = UUID.fromString(prepared.id());

        // 固定时钟不推进：将准备时限压缩为 0 触发超时收敛
        properties.setPublishInstanceTimeoutSeconds(0);
        service.sweepUnfinished(null);

        assertThat(snapshots.snapshots.get(1L).status()).isEqualTo(ConfigSnapshotRecord.STATUS_ABORTED);
        PublishRecordRecord record = publishes.records.get(publishId);
        assertThat(record.status()).isEqualTo(PublishRecordRecord.STATUS_FAILED);
        assertThat(record.errorCode()).isEqualTo("INSTANCE_PREPARE_TIMEOUT");
        assertThat(draftState.status).isEqualTo(com.lightai.storage.draft.DraftStatus.EDITABLE);
    }

    @Test
    void activatingTimeoutBecomesPartialFailedAndKeepsActiveSnapshot() {
        ConfigValidationRecord validation = seedValidation();
        PublishRecordDetailView prepared = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null));
        UUID publishId = UUID.fromString(prepared.id());
        service.applyReport(publishId, instanceId, new InstanceLoadReport(1,
                PublishInstanceResultRecord.STATUS_READY, OffsetDateTime.now(CLOCK), 0, null, null, null));

        // 固定时钟不推进：将加载时限压缩为 0 触发首轮超时（激活后不回滚快照）
        properties.setPublishInstanceTimeoutSeconds(0);
        service.sweepUnfinished(null);

        assertThat(snapshots.snapshots.get(1L).status()).isEqualTo(ConfigSnapshotRecord.STATUS_ACTIVE);
        PublishRecordRecord record = publishes.records.get(publishId);
        assertThat(record.status()).isEqualTo(PublishRecordRecord.STATUS_PARTIAL_FAILED);
        assertThat(record.convergedAt()).isNull();
        assertThat(draftState.status).isEqualTo(com.lightai.storage.draft.DraftStatus.EDITABLE);
    }

    @Test
    void publishHistoryAndSnapshotSummaryMapToWireShapes() {
        ConfigValidationRecord validation = seedValidation();
        PublishRecordDetailView prepared = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null));

        var page = service.records(null, null, null, null, null, null, 1, 20);
        assertThat(page.total()).isEqualTo(1);
        PublishRecordListItemView item = page.items().get(0);
        assertThat(item.id()).isEqualTo(prepared.id());
        assertThat(item.snapshotNo()).isEqualTo(1);
        assertThat(item.publishedByName()).isEqualTo("admin");

        var summary = service.snapshotSummary(1);
        assertThat(summary.snapshotNo()).isEqualTo(1);
        assertThat(summary.status()).isEqualTo(ConfigSnapshotRecord.STATUS_CREATED);
        assertThat(summary.contentChecksum()).isNotBlank();
        assertThat(summary.configCounts()).containsEntry("providers", 1L);

        var instancePage = service.runtimeInstances(null, null, null, 1, 20);
        assertThat(instancePage.items()).hasSize(1);
        assertThat(instancePage.items().get(0).status()).isEqualTo("ONLINE");
    }

    @Test
    void snapshotContentRequiresActiveOrPreparedReferenceAndMatchingChecksum() {
        ConfigValidationRecord validation = seedValidation();
        PublishRecordDetailView prepared = service.publish("req-1", "admin", "203.0.113.*",
                new ConfigPublishCommand(validation.validationId().toString(), 5, List.of(), null));
        String checksum = prepared.contentChecksum();

        // 校验和不一致拒绝
        assertThatThrownBy(() -> service.snapshotContent(instanceId, 1, "wrong-checksum"))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.SNAPSHOT_CHECKSUM_MISMATCH);

        // 准备中的目标快照允许实例读取
        var content_ = service.snapshotContent(instanceId, 1, checksum);
        assertThat(content_.snapshotNo()).isEqualTo(1);
        assertThat(content_.content()).isInstanceOf(Map.class);

        // 与发布无关的实例读取非活动快照被拒
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> service.snapshotContent(stranger, 1, checksum))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    private void assertFailureAudit(String requestId, String errorCode) {
        assertThat(audits.inserted).hasSize(1);
        assertThat(audits.inserted.get(0).result()).isEqualTo(AuditRecord.RESULT_FAILED);
        assertThat(audits.inserted.get(0).errorCode()).isEqualTo(errorCode);
    }

    private static ConfigValidationRecord shiftExpiry(ConfigValidationRecord record,
                                                      OffsetDateTime expiresAt) {
        return new ConfigValidationRecord(record.validationId(), record.baseSnapshotNo(),
                record.targetSnapshotNo(), record.draftRevision(), record.contentChecksum(),
                record.status(), record.errorCount(), record.warningCount(), record.validatedAt(),
                expiresAt, record.validatedBy(), record.usedByPublishId(),
                record.changeSummaryJson(), record.affectedAliasIds(), record.targetInstancesJson());
    }

    private static ConfigValidationRecord withCounts(ConfigValidationRecord record,
                                                     int errorCount, int warningCount) {
        return new ConfigValidationRecord(record.validationId(), record.baseSnapshotNo(),
                record.targetSnapshotNo(), record.draftRevision(), record.contentChecksum(),
                record.status(), errorCount, warningCount, record.validatedAt(),
                record.expiresAt(), record.validatedBy(), record.usedByPublishId(),
                record.changeSummaryJson(), record.affectedAliasIds(), record.targetInstancesJson());
    }

    private static String ConfigValidationIssueViewSeverity() {
        return com.lightai.client.publish.ConfigValidationIssueView.SEVERITY_WARNING;
    }
}
