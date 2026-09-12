package com.lightai.admin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.AdminProperties;
import com.lightai.admin.publish.PublishTestSupport.FakeInstanceResultRepository;
import com.lightai.admin.publish.PublishTestSupport.FakePublishRecordRepository;
import com.lightai.admin.publish.PublishTestSupport.FakeRuntimeInstanceRepository;
import com.lightai.admin.publish.PublishTestSupport.FakeSnapshotRepository;
import com.lightai.admin.publish.PublishTestSupport.FakeValidationRepository;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.ConfigRollbackCommand;
import com.lightai.client.publish.InstanceLoadReport;
import com.lightai.storage.publish.ConfigSnapshotRecord;
import com.lightai.storage.publish.PublishInstanceResultRecord;
import com.lightai.storage.publish.PublishRecordRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BE-233 回滚状态机测试（BE-P23）：回滚生成新记录并复用准备→激活→实例确认流程；
 * 幂等重放返回同一记录；失败路径保留旧活动版本。
 */
class ConfigReleaseRollbackTest {

    private ConfigPublishService publishService;
    private FakePublishRecordRepository publishes;
    private FakeInstanceResultRepository instanceResults;
    private FakeSnapshotRepository snapshots;
    private FakeRuntimeInstanceRepository instances;
    private PublishTestSupport.RecordingAuditRepository audits;
    private UUID instanceId;
    private PublishTestSupport.RecordingConnection recording;

    @BeforeEach
    void setUp() {
        recording = new PublishTestSupport.RecordingConnection();
        var draftState = new PublishTestSupport.FakeDraftStateRepository(recording.calls);
        var changes = new PublishTestSupport.FakeDraftChangeQueryRepository(recording.calls);
        snapshots = new FakeSnapshotRepository(recording.calls);
        var content = new PublishTestSupport.FakeSnapshotContentRepository(recording.calls);
        content.content = ConfigValidationServiceTest.validContent();
        var validations = new FakeValidationRepository(recording.calls);
        publishes = new FakePublishRecordRepository(recording.calls);
        instanceResults = new FakeInstanceResultRepository(recording.calls);
        instances = new FakeRuntimeInstanceRepository(recording.calls);
        instanceId = UUID.randomUUID();
        instances.online(instanceId);
        audits = new PublishTestSupport.RecordingAuditRepository(recording.calls);
        var dependencies = new PublishTestSupport.FakeDependencyRepository(recording.calls);
        var transactionManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                recording.dataSource());
        transactionManager.afterPropertiesSet();
        var auditService = new com.lightai.admin.audit.AuditService(audits,
                recording.dataSource(), transactionManager, new PublishTestSupport.FailureCollector());

        // 历史快照：3 号为已发布后被替换的历史版本，5 号为当前活动版本
        snapshots.insert(null, snapshot(3, ConfigSnapshotRecord.STATUS_SUPERSEDED));
        snapshots.insert(null, snapshot(5, ConfigSnapshotRecord.STATUS_ACTIVE));
        // 最近一次成功发布：3 号 → 5 号
        publishes.insert(null, new PublishRecordRecord(UUID.randomUUID(), UUID.randomUUID(),
                3, 5, 5, PublishRecordRecord.STATUS_SUCCEEDED, "admin", "初始发布",
                List.of(), List.of(instanceId), OffsetDateTime.now(), null, 1000L,
                null, null, OffsetDateTime.now(), OffsetDateTime.now()));

        var webProperties = new AdminProperties();
        webProperties.setRuntimeMode("STANDALONE_SERVER");
        publishService = new ConfigPublishService(recording.dataSource(), transactionManager,
                java.time.Clock.systemUTC(), draftState, draftState, changes, snapshots, content,
                validations, publishes, instanceResults, instances, auditService, webProperties,
                com.lightai.runtime.ports.ConfigSnapshotPort.empty());
    }

    private static ConfigSnapshotRecord snapshot(long no, String status) {
        return new ConfigSnapshotRecord(no, 1, status, "{\"schema_version\":1}",
                "chk-" + no, "{\"channels\":1}", null, "admin",
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void rollbackCreatesNewPreparingRecordReusingStateMachine() {
        var detail = publishService.rollback("req-rb-1", "admin", "127.0.0.*",
                new ConfigRollbackCommand(3L, "回滚到稳定版本", "rb-key-1"));
        assertThat(detail.status()).isEqualTo(PublishRecordRecord.STATUS_PREPARING);
        assertThat(detail.targetSnapshotNo()).isEqualTo(3);
        assertThat(detail.fromSnapshotNo()).isEqualTo(5);
        assertThat(detail.publishNote()).isEqualTo("回滚到稳定版本");
        var results = publishService.instanceResults(UUID.fromString(detail.id()));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(PublishInstanceResultRecord.STATUS_PENDING);
        assertThat(results.get(0).targetSnapshotNo()).isEqualTo(3);
        // 回滚动作已审计
        assertThat(audits.inserted).anyMatch(record ->
                "PUBLISH_ROLLBACK".equals(record.action()));
        // 活动版本未被回滚请求立即改变（等待实例确认）
        assertThat(snapshots.find(null, 5).orElseThrow().status())
                .isEqualTo(ConfigSnapshotRecord.STATUS_ACTIVE);
    }

    @Test
    void rollbackReplaysSameRecordForSameIdempotencyKey() {
        var first = publishService.rollback("req-rb-2", "admin", "127.0.0.*",
                new ConfigRollbackCommand(3L, "回滚", "rb-key-2"));
        var replay = publishService.rollback("req-rb-3", "admin", "127.0.0.*",
                new ConfigRollbackCommand(3L, "回滚", "rb-key-2"));
        assertThat(replay.id()).isEqualTo(first.id());
        // 首条回滚仍处 PREPARING：不同幂等键的第二次回滚被并发保护拒绝
        assertThatThrownBy(() -> publishService.rollback("req-rb-4", "admin", "127.0.0.*",
                new ConfigRollbackCommand(3L, "再次回滚", "rb-key-3")))
                .isInstanceOfSatisfying(LightAiException.class, e ->
                        assertThat(e.code()).isEqualTo(com.lightai.client.error.ErrorCode.CONFIG_PUBLISH_IN_PROGRESS));
    }

    @Test
    void rollbackRejectsActiveTargetUnknownSnapshotAndMissingKey() {
        assertThatThrownBy(() -> publishService.rollback("req-rb-5", "admin", "127.0.0.*",
                new ConfigRollbackCommand(5L, "回滚到当前版本", "rb-key-5")))
                .isInstanceOfSatisfying(LightAiException.class, e ->
                        assertThat(e.code()).isEqualTo(com.lightai.client.error.ErrorCode.FIELD_VALIDATION_FAILED));
        assertThatThrownBy(() -> publishService.rollback("req-rb-6", "admin", "127.0.0.*",
                new ConfigRollbackCommand(99L, "回滚到不存在版本", "rb-key-6")))
                .isInstanceOfSatisfying(LightAiException.class, e ->
                        assertThat(e.code()).isEqualTo(com.lightai.client.error.ErrorCode.OBJECT_NOT_FOUND));
        assertThatThrownBy(() -> publishService.rollback("req-rb-7", "admin", "127.0.0.*",
                new ConfigRollbackCommand(3L, "缺少幂等键", " ")))
                .isInstanceOfSatisfying(LightAiException.class, e ->
                        assertThat(e.code()).isEqualTo(com.lightai.client.error.ErrorCode.FIELD_VALIDATION_FAILED));
        // 全部失败路径：活动版本保持不变，未产生新发布记录
        assertThat(snapshots.find(null, 5).orElseThrow().status())
                .isEqualTo(ConfigSnapshotRecord.STATUS_ACTIVE);
        assertThat(publishes.records).hasSize(1);
    }

    @Test
    void rollbackUsesLastPublishSourceWhenTargetOmitted() {
        var detail = publishService.rollback("req-rb-8", "admin", "127.0.0.*",
                new ConfigRollbackCommand(null, "默认回滚上一版本", "rb-key-8"));
        assertThat(detail.targetSnapshotNo()).isEqualTo(3);
        assertThat(detail.fromSnapshotNo()).isEqualTo(5);
    }

    @Test
    void rollbackCompletesThroughActivationAndLoadConfirmation() {
        var detail = publishService.rollback("req-rb-9", "admin", "127.0.0.*",
                new ConfigRollbackCommand(3L, "回滚", "rb-key-9"));
        UUID publishId = UUID.fromString(detail.id());
        OffsetDateTime now = OffsetDateTime.now();
        // 实例全部 READY → 激活历史快照
        publishService.applyReport(publishId, instanceId,
                new InstanceLoadReport(3, PublishInstanceResultRecord.STATUS_READY, now, 0, null, null, null));
        assertThat(snapshots.find(null, 3).orElseThrow().status())
                .isEqualTo(ConfigSnapshotRecord.STATUS_ACTIVE);
        assertThat(snapshots.find(null, 5).orElseThrow().status())
                .isEqualTo(ConfigSnapshotRecord.STATUS_SUPERSEDED);
        // 实例加载完成 → 发布记录收敛为 SUCCEEDED
        publishService.applyReport(publishId, instanceId,
                new InstanceLoadReport(3, PublishInstanceResultRecord.STATUS_LOADED, now, 0, 12L, null, null));
        assertThat(publishes.records.get(publishId).status())
                .isEqualTo(PublishRecordRecord.STATUS_SUCCEEDED);
    }
}
