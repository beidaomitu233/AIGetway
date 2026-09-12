package com.lightai.admin.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lightai.admin.audit.AuditService;
import com.lightai.admin.AdminProperties;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.ConfigPublishCommand;
import com.lightai.client.publish.ConfigRollbackCommand;
import com.lightai.client.publish.ConfigValidationIssueView;
import com.lightai.client.publish.ConfigSnapshotContentView;
import com.lightai.client.publish.ConfigSnapshotSummaryView;
import com.lightai.client.publish.InstanceActivationCommand;
import com.lightai.client.publish.InstanceLoadReport;
import com.lightai.client.publish.InstancePrepareCommand;
import com.lightai.client.publish.PublishInstanceResultView;
import com.lightai.client.publish.PublishRecordDetailView;
import com.lightai.client.publish.PublishRecordListItemView;
import com.lightai.client.publish.RuntimeHeartbeatResponse;
import com.lightai.client.publish.RuntimeInstanceHeartbeat;
import com.lightai.client.publish.RuntimeInstanceView;
import com.lightai.client.paging.PageResult;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.draft.DraftChangeQueryRepository;
import com.lightai.storage.draft.DraftPublishStateRepository;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.draft.DraftStateSnapshot;
import com.lightai.storage.draft.DraftStatus;
import com.lightai.storage.publish.ConfigSnapshotRecord;
import com.lightai.storage.publish.ConfigValidationIssueRecord;
import com.lightai.storage.publish.ConfigValidationRecord;
import com.lightai.storage.publish.ConfigSnapshotRepository;
import com.lightai.storage.publish.ConfigValidationRepository;
import com.lightai.storage.publish.PublishInstanceResultRepository;
import com.lightai.storage.publish.PublishRecordRepository;
import com.lightai.storage.publish.RuntimeInstanceRepository;
import com.lightai.storage.publish.SnapshotContentRepository;
import com.lightai.storage.publish.PublishInstanceResultRecord;
import com.lightai.storage.publish.PublishRecordRecord;
import com.lightai.storage.publish.RuntimeInstanceRecord;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 发布协调（BE-040/BE-041/BE-042，4.5.2.4 两阶段加载）。
 * 准备事务：校验条件 → 锁草稿 PUBLISHING → CREATED 快照 + PREPARING 记录 + PENDING 实例结果；
 * 激活事务：全部 READY → 旧 ACTIVE SUPERSEDED + 目标 ACTIVE + 指针 + 草稿基线原子切换；
 * 收敛：实例 LOADED 后 SUCCEEDED；准备超时 ABORTED/FAILED 并释放草稿；未全部加载为 PARTIAL_FAILED。
 * 校验失败/非法命令不创建 PublishRecord（C-007）。
 */
public class ConfigPublishService {

    private static final int MAX_ERROR_SUMMARY = 1000;

    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final DraftStateRepository draftStateRepository;
    private final DraftPublishStateRepository draftPublishStateRepository;
    private final DraftChangeQueryRepository draftChangeQueryRepository;
    private final ConfigSnapshotRepository snapshotRepository;
    private final SnapshotContentRepository snapshotContentRepository;
    private final ConfigValidationRepository validationRepository;
    private final PublishRecordRepository publishRecordRepository;
    private final PublishInstanceResultRepository instanceResultRepository;
    private final RuntimeInstanceRepository runtimeInstanceRepository;
    private final AuditService auditService;
    private final AdminProperties properties;
    private final ConfigSnapshotPort snapshotPort;

    public ConfigPublishService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                Clock clock, DraftStateRepository draftStateRepository,
                                DraftPublishStateRepository draftPublishStateRepository,
                                DraftChangeQueryRepository draftChangeQueryRepository,
                                ConfigSnapshotRepository snapshotRepository,
                                SnapshotContentRepository snapshotContentRepository,
                                ConfigValidationRepository validationRepository,
                                PublishRecordRepository publishRecordRepository,
                                PublishInstanceResultRepository instanceResultRepository,
                                RuntimeInstanceRepository runtimeInstanceRepository,
                                AuditService auditService, AdminProperties properties,
                                ConfigSnapshotPort snapshotPort) {
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.draftStateRepository = draftStateRepository;
        this.draftPublishStateRepository = draftPublishStateRepository;
        this.draftChangeQueryRepository = draftChangeQueryRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotContentRepository = snapshotContentRepository;
        this.validationRepository = validationRepository;
        this.publishRecordRepository = publishRecordRepository;
        this.instanceResultRepository = instanceResultRepository;
        this.runtimeInstanceRepository = runtimeInstanceRepository;
        this.auditService = auditService;
        this.properties = properties;
        this.snapshotPort = snapshotPort;
    }

    // ---------- 发布提交（BE-040） ----------

    public PublishRecordDetailView publish(String requestId, String operatorId,
                                           String sourceIpMasked, ConfigPublishCommand command) {
        UUID validationId = parseId(command.validationId());
        try {
            return transaction.execute(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                OffsetDateTime now = OffsetDateTime.now(clock);
                validationRepository.sweepExpired(connection, now);

                ConfigValidationRepositoryHolder holder = requireUsableValidation(connection, validationId, now);
                ConfigValidationRecord validation = holder.validation();
                if (validation.usedByPublishId() != null) {
                    // 同一校验的重复提交：返回既有发布记录，不重建（4.5.6.1 / C-007）
                    PublishRecordRecord existing = publishRecordRepository
                            .findByValidation(connection, validationId)
                            .orElseThrow(() -> new LightAiException(ErrorCode.CONFIG_VALIDATION_EXPIRED,
                                    "校验已被使用但没有对应发布记录"));
                    return toDetail(connection, existing);
                }
                if (validation.draftRevision() != command.draftRevision()) {
                    throw new LightAiException(ErrorCode.CONFIG_DRAFT_CHANGED, "草稿修订与校验不一致，请重新校验");
                }
                DraftStateSnapshot locked = draftStateRepository.lock(connection);
                if (locked.status() == DraftStatus.PUBLISHING) {
                    throw new LightAiException(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS, "已有发布占用草稿锁");
                }
                if (locked.draftRevision() != command.draftRevision()) {
                    throw new LightAiException(ErrorCode.CONFIG_DRAFT_CHANGED, "草稿修订已变化，请重新校验");
                }
                Map<String, Object> content =
                        snapshotContentRepository.assemble(connection, properties.getTimezone());
                String canonical = snapshotContentRepository.canonicalJson(content);
                String checksum = ConfigValidationService.sha256Hex(canonical);
                if (!checksum.equals(validation.contentChecksum())) {
                    throw new LightAiException(ErrorCode.CONFIG_DRAFT_CHANGED, "草稿内容与校验不一致，请重新校验");
                }
                requireWarningsAcknowledged(holder.issues(), command.acknowledgedWarningIds());
                List<RuntimeInstanceRecord> targets = runtimeInstanceRepository.findOnline(connection);
                if (targets.isEmpty()) {
                    throw new LightAiException(ErrorCode.NO_ONLINE_RUNTIME_INSTANCE,
                            "当前没有可参与发布的在线运行实例");
                }

                UUID publishId = UUID.randomUUID();
                draftPublishStateRepository.markPublishing(connection, publishId);
                long targetSnapshotNo = snapshotRepository.nextSnapshotNo(connection);
                snapshotRepository.insert(connection, new ConfigSnapshotRecord(
                        targetSnapshotNo, 1, ConfigSnapshotRecord.STATUS_CREATED, canonical,
                        checksum, summaryJson(content), null, operatorId, now, now));
                PublishRecordRecord record = new PublishRecordRecord(
                        publishId, validationId, locked.baseSnapshotNo(), targetSnapshotNo,
                        command.draftRevision(), PublishRecordRecord.STATUS_PREPARING,
                        operatorId, command.publishNote(), command.acknowledgedWarningIds(),
                        targets.stream().map(RuntimeInstanceRecord::instanceId).toList(),
                        null, null, null, null, null, now, now);
                publishRecordRepository.insert(connection, record);
                instanceResultRepository.insertPending(connection, publishId,
                        locked.baseSnapshotNo(), targetSnapshotNo,
                        targets.stream().map(RuntimeInstanceRecord::instanceId).toList());
                validationRepository.markUsed(connection, validationId, publishId);
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), requestId, operatorId, "PUBLISH",
                        "publish_record", publishId.toString(), List.of(),
                        properties.getRuntimeMode(), sourceIpMasked));
                return toDetail(connection, record);
            });
        } catch (LightAiException e) {
            recordFailure(requestId, operatorId, properties.getRuntimeMode(), sourceIpMasked, e, validationId);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(requestId, operatorId, properties.getRuntimeMode(), sourceIpMasked, e, validationId);
            throw e;
        }
    }

    // ---------- 查询（BE-042） ----------

    public PageResult<PublishRecordListItemView> records(String status, String publishedBy,
                                                         Long snapshotNo, String keyword,
                                                         OffsetDateTime startFrom,
                                                         OffsetDateTime startTo,
                                                         int page, int pageSize) {
        var filter = new PublishRecordRepository.PublishRecordFilter(
                status == null || status.isBlank() ? Set.of() : Set.of(status),
                blankToNull(publishedBy), snapshotNo, startFrom, startTo, blankToNull(keyword));
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            long total = publishRecordRepository.count(connection, filter);
            List<PublishRecordRecord> rows = publishRecordRepository.list(connection, filter,
                    "created_at desc", pageSize, (long) (page - 1) * pageSize);
            List<PublishRecordListItemView> items = rows.stream().map(ConfigPublishService::toListItem).toList();
            return PageResult.of(items, total, page, pageSize, "created_at desc",
                    OffsetDateTime.now(clock), OffsetDateTime.now(clock));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    public PublishRecordDetailView recordDetail(UUID id) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            PublishRecordRecord record = publishRecordRepository.find(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "发布记录不存在"));
            return toDetail(connection, record);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /** 指定发布记录的全部实例结果（/admin/config-releases/{id}/instances）。 */
    public List<PublishInstanceResultView> instanceResults(UUID publishId) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            publishRecordRepository.find(connection, publishId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "发布记录不存在"));
            return instanceResultRepository.listByPublish(connection, publishId).stream()
                    .map(ConfigPublishService::toInstanceView).toList();
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    // ---------- 回滚（BE-233；PRD 9.5：回滚生成新发布记录） ----------

    private static final long ROLLBACK_LINK_RETENTION_SECONDS = 7L * 24 * 3600;

    /**
     * 回滚 = 以新发布记录重新激活历史不可变快照，完整复用准备→激活→实例确认状态机；
     * 失败保留旧活动版本。幂等键经确定性 validation 行桥接（publish_record.validation_id
     * NOT NULL 的过渡实现，BE-P23-001 登记 DB-P23 迁移建议）：作用域为回滚操作+目标快照，
     * 同键同目标重放返回同一记录；同键不同目标视为新请求（键持久化列待 DB-P23）。
     */
    public PublishRecordDetailView rollback(String requestId, String operatorId,
                                            String sourceIpMasked, ConfigRollbackCommand command) {
        try {
            return transaction.execute(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                OffsetDateTime now = OffsetDateTime.now(clock);
                sweepUnfinished(connection);
                if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                        || command.idempotencyKey().length() > 128) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                            "idempotency_key 必填（1～128 字符）",
                            List.of(new FieldIssue("idempotency_key", "REQUIRED",
                                    "idempotency_key 必填（1～128 字符）")));
                }
                if (command.reason() == null || command.reason().isBlank()) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "reason 必填",
                            List.of(new FieldIssue("reason", "REQUIRED", "reason 必填")));
                }
                ConfigSnapshotRecord active = snapshotRepository.findActive(connection)
                        .orElseThrow(() -> new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE,
                                "当前没有活动快照"));
                long target = command.targetSnapshotNo() == null
                        ? previousPublishedSource(connection, active.snapshotNo())
                        : command.targetSnapshotNo();
                if (target == active.snapshotNo()) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                            "回滚目标不能是当前活动版本",
                            List.of(new FieldIssue("target_snapshot_no", "INVALID",
                                    "目标快照已是活动版本")));
                }
                ConfigSnapshotRecord targetSnapshot = snapshotRepository.find(connection, target)
                        .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "目标快照不存在"));
                if (!ConfigSnapshotRecord.STATUS_SUPERSEDED.equals(targetSnapshot.status())) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                            "回滚目标必须是历史已发布快照",
                            List.of(new FieldIssue("target_snapshot_no", "INVALID",
                                    "目标快照状态为 " + targetSnapshot.status())));
                }
                UUID validationId = UUID.nameUUIDFromBytes(("ROLLBACK:" + target + ":"
                        + command.idempotencyKey().trim()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var replay = publishRecordRepository.findByValidation(connection, validationId);
                if (replay.isPresent()) {
                    return toDetail(connection, replay.get());
                }
                if (!publishRecordRepository.listUnfinished(connection).isEmpty()) {
                    throw new LightAiException(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS,
                            "已有发布或回滚进行中");
                }
                DraftStateSnapshot locked = draftStateRepository.lock(connection);
                if (locked.status() == DraftStatus.PUBLISHING) {
                    throw new LightAiException(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS, "已有发布占用草稿锁");
                }
                List<RuntimeInstanceRecord> targets = runtimeInstanceRepository.findOnline(connection);
                if (targets.isEmpty()) {
                    throw new LightAiException(ErrorCode.NO_ONLINE_RUNTIME_INSTANCE,
                            "当前没有可参与回滚的在线运行实例");
                }
                if (validationRepository.find(connection, validationId).isEmpty()) {
                    validationRepository.insert(connection, new ConfigValidationRecord(
                            validationId, active.snapshotNo(), target, locked.draftRevision(),
                            targetSnapshot.contentChecksum(), ConfigValidationRecord.STATUS_PASSED,
                            0, 0, now, now.plusSeconds(ROLLBACK_LINK_RETENTION_SECONDS), operatorId,
                            null, rollbackLinkJson(command, targetSnapshot), List.of(), "[]"), List.of());
                }
                UUID publishId = UUID.randomUUID();
                draftPublishStateRepository.markPublishing(connection, publishId);
                PublishRecordRecord record = new PublishRecordRecord(
                        publishId, validationId, active.snapshotNo(), target,
                        locked.draftRevision(), PublishRecordRecord.STATUS_PREPARING,
                        operatorId, command.reason(), List.of(),
                        targets.stream().map(RuntimeInstanceRecord::instanceId).toList(),
                        null, null, null, null, null, now, now);
                publishRecordRepository.insert(connection, record);
                instanceResultRepository.insertPending(connection, publishId,
                        active.snapshotNo(), target,
                        targets.stream().map(RuntimeInstanceRecord::instanceId).toList());
                validationRepository.markUsed(connection, validationId, publishId);
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), requestId, operatorId, "PUBLISH_ROLLBACK",
                        "publish_record", publishId.toString(), List.of(),
                        properties.getRuntimeMode(), sourceIpMasked));
                return toDetail(connection, record);
            });
        } catch (LightAiException e) {
            recordFailure(requestId, operatorId, properties.getRuntimeMode(), sourceIpMasked, e, null);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(requestId, operatorId, properties.getRuntimeMode(), sourceIpMasked, e, null);
            throw e;
        }
    }

    /** 最近一次发布到 currentActive 的来源快照；无历史发布时拒绝默认回滚。 */
    private long previousPublishedSource(Connection connection, long currentActive) {
        var filter = new PublishRecordRepository.PublishRecordFilter(
                Set.of(PublishRecordRecord.STATUS_SUCCEEDED), null, currentActive, null, null, null);
        List<PublishRecordRecord> rows = publishRecordRepository.list(connection, filter,
                "created_at desc", 1, 0L);
        if (rows.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "没有可回滚的历史版本，请显式指定 target_snapshot_no",
                    List.of(new FieldIssue("target_snapshot_no", "REQUIRED",
                            "没有发布到当前活动版本的成功记录")));
        }
        return rows.get(0).fromSnapshotNo();
    }

    /** 桥接 validation 行的变更摘要：只含操作元数据，不含密钥或正文。 */
    private static String rollbackLinkJson(ConfigRollbackCommand command,
                                           ConfigSnapshotRecord target) {
        return "{\"operation\":\"ROLLBACK\",\"target_snapshot_no\":" + target.snapshotNo()
                + ",\"content_checksum\":\"" + target.contentChecksum() + "\"}";
    }

    public ConfigSnapshotSummaryView snapshotSummary(long snapshotNo) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ConfigSnapshotRecord snapshot = snapshotRepository.find(connection, snapshotNo)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "快照不存在"));
            Map<String, Long> counts = parseCounts(snapshot.contentSummaryJson());
            return new ConfigSnapshotSummaryView(snapshot.snapshotNo(), snapshot.status(),
                    snapshot.createdAt(), snapshot.activatedAt(), snapshot.contentChecksum(), counts);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    public PageResult<RuntimeInstanceView> runtimeInstances(String status, String runtimeMode,
                                                            String application, int page, int pageSize) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            var filter = new RuntimeInstanceRepository.RuntimeInstanceFilter(
                    status == null || status.isBlank() ? Set.of() : Set.of(status),
                    blankToNull(runtimeMode), blankToNull(application));
            long total = runtimeInstanceRepository.count(connection, filter);
            List<RuntimeInstanceRecord> rows = runtimeInstanceRepository.list(connection, filter,
                    "last_heartbeat_at desc", pageSize, (long) (page - 1) * pageSize);
            List<RuntimeInstanceView> items = rows.stream().map(ConfigPublishService::toInstanceView).toList();
            return PageResult.of(items, total, page, pageSize, "last_heartbeat_at desc",
                    OffsetDateTime.now(clock), OffsetDateTime.now(clock));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    // ---------- 内部实例接口（BE-041） ----------

    /** 心跳：upsert 实例并返回准备/激活命令（互斥，可均空）。 */
    public RuntimeHeartbeatResponse heartbeat(RuntimeInstanceHeartbeat heartbeat) {
        return transaction.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            OffsetDateTime now = OffsetDateTime.now(clock);
            runtimeInstanceRepository.sweepStale(connection, properties.getInstanceStaleSeconds());
            runtimeInstanceRepository.upsertHeartbeat(connection, new RuntimeInstanceRecord(
                    UUID.fromString(heartbeat.instanceId()), heartbeat.runtimeMode(),
                    heartbeat.runtimeVersion(), heartbeat.application(), heartbeat.zone(),
                    heartbeat.supportedSchemaVersions(), heartbeat.loadedAdapterTypes(),
                    heartbeat.activeSnapshotNo(), heartbeat.acceptingRequests(),
                    heartbeat.acceptingRequests() ? "ONLINE" : "DRAINING",
                    now, null, null, now));
            return commandsFor(connection, UUID.fromString(heartbeat.instanceId()), now);
        });
    }

    /** 内部快照读取：仅活动快照或被准备引用的快照可读；checksum 不一致拒绝。 */
    public ConfigSnapshotContentView snapshotContent(UUID instanceId, long snapshotNo,
                                                     String expectedChecksum) {
        return transaction.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            sweepUnfinished(connection);
            ConfigSnapshotRecord snapshot = snapshotRepository.find(connection, snapshotNo)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "快照不存在"));
            if (!ConfigSnapshotRecord.STATUS_ACTIVE.equals(snapshot.status())
                    && !instanceReferencedByPendingPrepare(connection, instanceId, snapshotNo)) {
                throw new LightAiException(ErrorCode.ACCESS_DENIED, "实例未被授权读取该快照");
            }
            if (expectedChecksum != null && !expectedChecksum.isBlank()
                    && !expectedChecksum.equals(snapshot.contentChecksum())) {
                throw new LightAiException(ErrorCode.SNAPSHOT_CHECKSUM_MISMATCH,
                        "快照内容摘要与发布指令不一致");
            }
            Map<String, Object> content = parseContent(snapshot.contentJson());
            return new ConfigSnapshotContentView(snapshot.snapshotNo(), snapshot.schemaVersion(),
                    snapshot.status(), snapshot.contentChecksum(), content, snapshot.createdAt());
        });
    }

    /** 实例加载上报：时序冲突 INSTANCE_REPORT_CONFLICT；全 READY 触发激活；全 LOADED 收敛。 */
    public PublishInstanceResultView applyReport(UUID publishId, UUID instanceId,
                                                 InstanceLoadReport report) {
        return transaction.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            OffsetDateTime now = OffsetDateTime.now(clock);
            PublishRecordRecord record = publishRecordRepository.find(connection, publishId)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "发布记录不存在"));
            PublishInstanceResultRecord result =
                    instanceResultRepository.find(connection, publishId, instanceId)
                            .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND,
                                    "发布实例结果不存在"));
            if (report.targetSnapshotNo() != result.targetSnapshotNo()) {
                throw new LightAiException(ErrorCode.INSTANCE_REPORT_CONFLICT,
                        "上报目标快照与发布不一致");
            }
            if (result.reportedAt() != null && report.reportedAt() != null
                    && report.reportedAt().isBefore(result.reportedAt())) {
                throw new LightAiException(ErrorCode.INSTANCE_REPORT_CONFLICT, "旧上报被拒绝");
            }
            String target = report.status();
            boolean allowed = switch (target) {
                case PublishInstanceResultRecord.STATUS_READY -> !result.status()
                        .equals(PublishInstanceResultRecord.STATUS_LOADED)
                        && !result.status().equals(PublishInstanceResultRecord.STATUS_ACTIVATING);
                case PublishInstanceResultRecord.STATUS_FAILED,
                     PublishInstanceResultRecord.STATUS_TIMED_OUT ->
                        result.status().equals(PublishInstanceResultRecord.STATUS_PENDING)
                                || result.status().equals(PublishInstanceResultRecord.STATUS_PREPARING)
                                || result.status().equals(PublishInstanceResultRecord.STATUS_FAILED)
                                || result.status().equals(PublishInstanceResultRecord.STATUS_TIMED_OUT);
                case PublishInstanceResultRecord.STATUS_LOADED ->
                        record.status().equals(PublishRecordRecord.STATUS_ACTIVATING);
                default -> false;
            };
            if (!allowed) {
                throw new LightAiException(ErrorCode.INSTANCE_REPORT_CONFLICT,
                        "上报状态 " + target + " 与当前阶段 " + record.status() + "/"
                                + result.status() + " 冲突");
            }
            instanceResultRepository.applyReport(connection, publishId, instanceId, target,
                    report.reportedAt() == null ? now : report.reportedAt(), report.retryCount(),
                    report.loadDurationMs(), report.errorCode(), report.errorSummary());

            List<PublishInstanceResultRecord> results =
                    instanceResultRepository.listByPublish(connection, publishId);
            if (PublishInstanceResultRecord.STATUS_READY.equals(target)
                    && record.status().equals(PublishRecordRecord.STATUS_PREPARING)
                    && results.stream().allMatch(item ->
                            PublishInstanceResultRecord.STATUS_READY.equals(item.status()))) {
                activate(connection, publishId, record, results, now);
            } else if (PublishInstanceResultRecord.STATUS_LOADED.equals(target)
                    && results.stream().allMatch(item ->
                            PublishInstanceResultRecord.STATUS_LOADED.equals(item.status()))) {
                OffsetDateTime completedAt = record.completedAt() != null
                        ? record.completedAt() : now;
                long durationMs = record.durationMs() != null ? record.durationMs()
                        : Duration.between(record.createdAt(), now).toMillis();
                publishRecordRepository.updateOutcome(connection, publishId,
                        PublishRecordRecord.STATUS_SUCCEEDED, completedAt, now, durationMs,
                        null, null);
            }
            return toInstanceView(instanceResultRepository.find(connection, publishId, instanceId)
                    .orElseThrow());
        });
    }

    /** 惰性收敛扫描：准备超时中止并释放草稿；激活后超时进入 PARTIAL_FAILED（BE-042）。 */
    public void sweepUnfinished(Connection connection) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PublishRecordRecord> unfinished = publishRecordRepository.listUnfinished(connection);
        for (PublishRecordRecord record : unfinished) {
            OffsetDateTime deadline = record.createdAt()
                    .plusSeconds(properties.getPublishInstanceTimeoutSeconds());
            if (now.isBefore(deadline)) {
                continue;
            }
            List<PublishInstanceResultRecord> results =
                    instanceResultRepository.listByPublish(connection, record.id());
            if (record.status().equals(PublishRecordRecord.STATUS_PREPARING)) {
                instanceResultRepository.markAllStatus(connection, record.id(),
                        PublishInstanceResultRecord.STATUS_TIMED_OUT);
                snapshotRepository.transitionStatus(connection, record.targetSnapshotNo(),
                        ConfigSnapshotRecord.STATUS_ABORTED);
                draftPublishStateRepository.releaseToEditable(connection);
                publishRecordRepository.updateOutcome(connection, record.id(),
                        PublishRecordRecord.STATUS_FAILED, now, null,
                        Duration.between(record.createdAt(), now).toMillis(),
                        "INSTANCE_PREPARE_TIMEOUT", "实例准备超时，发布已中止且草稿保持可用");
            } else if (record.status().equals(PublishRecordRecord.STATUS_ACTIVATING)) {
                publishRecordRepository.updateOutcome(connection, record.id(),
                        PublishRecordRecord.STATUS_PARTIAL_FAILED, now, null,
                        Duration.between(record.createdAt(), now).toMillis(),
                        "INSTANCE_LOAD_TIMEOUT", "部分实例未在时限内确认加载，等待实例收敛");
            }
        }
    }

    /** 原子激活：唯一 ACTIVE + 指针 + 草稿基线 + 实例进入 ACTIVATING。 */
    private void activate(Connection connection, UUID publishId, PublishRecordRecord record,
                          List<PublishInstanceResultRecord> results, OffsetDateTime now) {
        publishRecordRepository.updateOutcome(connection, publishId,
                PublishRecordRecord.STATUS_ACTIVATING, null, null, null, null, null);
        // 普通发布激活 CREATED 快照；回滚激活 SUPERSEDED 历史快照（BE-233）
        if (snapshotRepository.find(connection, record.targetSnapshotNo())
                .map(ConfigSnapshotRecord::status)
                .map(ConfigSnapshotRecord.STATUS_SUPERSEDED::equals)
                .orElse(false)) {
            snapshotRepository.reactivate(connection, record.targetSnapshotNo());
        } else {
            snapshotRepository.activate(connection, record.targetSnapshotNo());
        }
        draftStateRepository.find(connection)
                .ifPresent(state -> draftPublishStateRepository.activateBaseline(
                        connection, record.targetSnapshotNo()));
        draftChangeQueryRepository.deleteAll(connection);
        instanceResultRepository.markAllStatus(connection, publishId,
                PublishInstanceResultRecord.STATUS_ACTIVATING);
        invalidateSnapshotCache();
    }

    private void invalidateSnapshotCache() {
        if (snapshotPort instanceof com.lightai.admin.publish.JdbcConfigSnapshotPortAdapter adapter) {
            adapter.invalidate();
        } else if (snapshotPort != null) {
            // 兜底：调用 active() 一次以触发其他实现的内部失效
            snapshotPort.active();
        }
    }

    /** 心跳命令装配：优先准备（PREPARING 且实例未 READY），激活需发布处于 ACTIVATING。 */
    private RuntimeHeartbeatResponse commandsFor(Connection connection, UUID instanceId,
                                                 OffsetDateTime now) {
        for (PublishRecordRecord record : publishRecordRepository.listUnfinished(connection)) {
            if (!record.targetInstanceIds().contains(instanceId)) {
                continue;
            }
            var result = instanceResultRepository.find(connection, record.id(), instanceId);
            String resultStatus = result.map(PublishInstanceResultRecord::status).orElse(null);
            if (record.status().equals(PublishRecordRecord.STATUS_PREPARING)
                    && (PublishInstanceResultRecord.STATUS_PENDING.equals(resultStatus)
                    || PublishInstanceResultRecord.STATUS_PREPARING.equals(resultStatus))) {
                instanceResultRepository.applyReport(connection, record.id(), instanceId,
                        PublishInstanceResultRecord.STATUS_PREPARING, now, 0, null, null, null);
                return new RuntimeHeartbeatResponse(now, activeSnapshotNo(connection),
                        new InstancePrepareCommand(record.id().toString(), record.targetSnapshotNo(),
                                checksumOf(connection, record.targetSnapshotNo()), 1,
                                record.createdAt().plusSeconds(properties.getPublishInstanceTimeoutSeconds())),
                        null);
            }
            if (record.status().equals(PublishRecordRecord.STATUS_ACTIVATING)
                    && (PublishInstanceResultRecord.STATUS_READY.equals(resultStatus)
                    || PublishInstanceResultRecord.STATUS_ACTIVATING.equals(resultStatus))) {
                return new RuntimeHeartbeatResponse(now, activeSnapshotNo(connection), null,
                        new InstanceActivationCommand(record.id().toString(),
                                record.targetSnapshotNo(),
                                checksumOf(connection, record.targetSnapshotNo())));
            }
        }
        return new RuntimeHeartbeatResponse(now, activeSnapshotNo(connection), null, null);
    }

    private long activeSnapshotNo(Connection connection) {
        return snapshotRepository.findActive(connection)
                .map(ConfigSnapshotRecord::snapshotNo).orElse(0L);
    }

    private String checksumOf(Connection connection, long snapshotNo) {
        return snapshotRepository.find(connection, snapshotNo)
                .map(ConfigSnapshotRecord::contentChecksum).orElse("");
    }

    private boolean instanceReferencedByPendingPrepare(Connection connection, UUID instanceId,
                                                       long snapshotNo) {
        for (PublishRecordRecord record : publishRecordRepository.listUnfinished(connection)) {
            if (record.targetSnapshotNo() == snapshotNo
                    && record.targetInstanceIds().contains(instanceId)) {
                return true;
            }
        }
        return false;
    }

    private ConfigValidationRepositoryHolder requireUsableValidation(Connection connection,
                                                                     UUID validationId,
                                                                     OffsetDateTime now) {
        var found = validationRepository.find(connection, validationId)
                .orElseThrow(() -> new LightAiException(ErrorCode.CONFIG_VALIDATION_EXPIRED,
                        "校验凭据不存在或已失效"));
        ConfigValidationRecord validation = found.validation();
        if (!ConfigValidationRecord.STATUS_PASSED.equals(validation.status())
                || validation.expiresAt().isBefore(now)) {
            throw new LightAiException(ErrorCode.CONFIG_VALIDATION_EXPIRED, "校验已过期或未通过");
        }
        // used_by_publish_id 的重复提交由调用方幂等返回既有记录，不在此拒绝
        return new ConfigValidationRepositoryHolder(validation, found.issues());
    }

    /** 警告确认完整性：前端以 issue.code 作为 acknowledged_warning_ids（PublishPage 契约）。 */
    private static void requireWarningsAcknowledged(List<ConfigValidationIssueRecord> issues,
                                                    List<String> acknowledged) {
        List<String> missing = issues.stream()
                .filter(issue -> ConfigValidationIssueView.SEVERITY_WARNING.equals(issue.severity()))
                .map(ConfigValidationIssueRecord::code)
                .filter(code -> !acknowledged.contains(code))
                .toList();
        if (!missing.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "存在未确认的发布警告",
                    List.of(new FieldIssue("acknowledged_warning_ids", "REQUIRED",
                            "缺少警告确认：" + String.join(",", missing))));
        }
    }

    private static String summaryJson(Map<String, Object> content) {
        StringBuilder json = new StringBuilder("{");
        content.forEach((key, value) -> {
            if (value instanceof List<?> rows && !key.equals("schema_version")) {
                if (json.length() > 1) {
                    json.append(',');
                }
                json.append('"').append(key).append("\":").append(rows.size());
            }
        });
        return json.append('}').toString();
    }

    private Map<String, Long> parseCounts(String json) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return counts;
        }
        try {
            Map<?, ?> raw = com.lightai.client.json.ProtocolJson.protocol()
                    .readValue(json, Map.class);
            raw.forEach((key, value) -> {
                if (value instanceof Number number) {
                    counts.put(String.valueOf(key), number.longValue());
                }
            });
            return counts;
        } catch (JsonProcessingException e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "快照摘要无法解析");
        }
    }

    private Map<String, Object> parseContent(String contentJson) {
        try {
            return com.lightai.client.json.ProtocolJson.protocol()
                    .readValue(contentJson, Map.class);
        } catch (JsonProcessingException e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "快照内容无法解析");
        }
    }

    private PublishRecordDetailView toDetail(Connection connection, PublishRecordRecord record) {
        List<PublishInstanceResultView> instanceResults =
                instanceResultRepository.listByPublish(connection, record.id()).stream()
                        .map(ConfigPublishService::toInstanceView).toList();
        return new PublishRecordDetailView(record.id().toString(), record.targetSnapshotNo(),
                record.fromSnapshotNo(), record.status(), record.publishedBy(), record.publishNote(),
                record.createdAt(), record.completedAt(), record.durationMs(),
                record.targetSnapshotNo(), record.draftRevision(), checksumOf(connection, record.targetSnapshotNo()),
                "", List.of(), record.acknowledgedWarningIds(), instanceResults,
                record.completedAt(), record.convergedAt());
    }

    private static PublishRecordListItemView toListItem(PublishRecordRecord record) {
        return new PublishRecordListItemView(record.id().toString(), record.targetSnapshotNo(),
                record.fromSnapshotNo(), record.status(), record.publishedBy(), record.publishNote(),
                record.createdAt(), record.completedAt(), record.durationMs());
    }

    private static PublishInstanceResultView toInstanceView(PublishInstanceResultRecord record) {
        return new PublishInstanceResultView(record.instanceId().toString(),
                record.runtimeMode(), record.runtimeVersion(), record.supportedSchemaVersions(),
                record.loadedAdapterTypes(), record.fromSnapshotNo(), record.targetSnapshotNo(),
                record.status(), record.retryCount(), record.loadDurationMs(),
                record.errorCode(), record.errorSummary(), record.updatedAt());
    }

    private static RuntimeInstanceView toInstanceView(RuntimeInstanceRecord record) {
        return new RuntimeInstanceView(record.instanceId().toString(), record.runtimeMode(),
                record.runtimeVersion(), record.application(), record.zone(), record.status(),
                record.acceptingRequests(), record.activeSnapshotNo(),
                record.supportedSchemaVersions(), record.loadedAdapterTypes(),
                record.lastHeartbeatAt());
    }

    private void recordFailure(String requestId, String operatorId, String sourceMode,
                               String sourceIpMasked, Exception cause, UUID validationId) {
        String code = cause instanceof LightAiException lightAi
                ? lightAi.code().name() : ErrorCode.INTERNAL_ERROR.name();
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        auditService.recordFailure(AuditRecord.failed(
                UUID.randomUUID(), requestId, operatorId, "PUBLISH", "config_draft_state",
                validationId == null ? null : validationId.toString(), code,
                message.length() <= MAX_ERROR_SUMMARY ? message : message.substring(0, MAX_ERROR_SUMMARY),
                sourceMode, sourceIpMasked));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "ID 不合法",
                    List.of(new FieldIssue("validation_id", "INVALID", "ID 必须是 UUID")));
        }
    }

    private record ConfigValidationRepositoryHolder(ConfigValidationRecord validation,
                                                    List<ConfigValidationIssueRecord> issues) {
    }
}
