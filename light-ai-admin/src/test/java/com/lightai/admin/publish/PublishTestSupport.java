package com.lightai.admin.publish;

import com.lightai.client.changes.FieldChange;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.audit.AuditRepository;
import com.lightai.storage.draft.DraftChangeQueryRepository;
import com.lightai.storage.draft.DraftChangeRow;
import com.lightai.storage.draft.DraftPublishStateRepository;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.draft.DraftStateSnapshot;
import com.lightai.storage.draft.DraftStatus;
import com.lightai.storage.publish.ConfigSnapshotRecord;
import com.lightai.storage.publish.ConfigValidationIssueRecord;
import com.lightai.storage.publish.ConfigValidationRecord;
import com.lightai.storage.publish.ConfigValidationRepository;
import com.lightai.storage.publish.PublishInstanceResultRecord;
import com.lightai.storage.publish.PublishInstanceResultRepository;
import com.lightai.storage.publish.PublishRecordRecord;
import com.lightai.storage.publish.PublishRecordRepository;
import com.lightai.storage.publish.RuntimeInstanceRecord;
import com.lightai.storage.publish.RuntimeInstanceRepository;
import com.lightai.storage.publish.SnapshotContentRepository;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * BE-P07 测试支撑：单连接事务记录器与内存假仓储。
 * 事务语义断言沿用 DraftWriteServiceTest 口径：业务失败必须以 rollback 结束，
 * 失败审计随后以独立事务 begin/insert/commit。
 */
final class PublishTestSupport {


    /** 单连接事务记录器。 */
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
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setAutoCommit" -> {
                            if (!(Boolean) args[0]) {
                                calls.add("begin");
                                inTransaction.set(true);
                            } else {
                                inTransaction.set(false);
                            }
                            yield null;
                        }
                        case "getAutoCommit" -> !inTransaction.get();
                        case "commit" -> {
                            calls.add("commit");
                            inTransaction.set(false);
                            onCommit.run();
                            yield null;
                        }
                        case "rollback" -> {
                            calls.add("rollback");
                            inTransaction.set(false);
                            onRollback.run();
                            yield null;
                        }
                        case "isClosed" -> false;
                        case "close" -> null;
                        default -> method.getDefaultValue() != null ? method.getDefaultValue() : null;
                    });
        }

        SingleConnectionDataSource dataSource() {
            return new SingleConnectionDataSource(connection(), true);
        }
    }

    /** 草稿状态假仓储（双端口）。 */
    static final class FakeDraftStateRepository implements DraftStateRepository, DraftPublishStateRepository {
        long revision = 5;
        int changeCount = 2;
        DraftStatus status = DraftStatus.EDITABLE;
        long baseSnapshotNo = 0;
        final List<Integer> bumpDeltas = new ArrayList<>();
        final List<String> publishEvents = new ArrayList<>();
        private final List<String> calls;
        private int pendingDelta;
        private Consumer<String> onEvent = event -> {
        };

        FakeDraftStateRepository(List<String> calls) {
            this.calls = calls;
        }

        void onEvent(Consumer<String> onEvent) {
            this.onEvent = onEvent;
        }

        @Override
        public Optional<DraftStateSnapshot> find(Connection connection) {
            return Optional.of(snapshot());
        }

        @Override
        public DraftStateSnapshot lock(Connection connection) {
            calls.add("lock-draft");
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

        @Override
        public void markPublishing(Connection connection, UUID publishRecordId) {
            calls.add("mark-publishing");
            status = DraftStatus.PUBLISHING;
            publishEvents.add("PUBLISHING:" + publishRecordId);
            onEvent.accept("PUBLISHING");
        }

        @Override
        public void releaseToEditable(Connection connection) {
            calls.add("release-editable");
            status = DraftStatus.EDITABLE;
            onEvent.accept("RELEASED");
        }

        @Override
        public void activateBaseline(Connection connection, long targetSnapshotNo) {
            calls.add("activate-baseline");
            baseSnapshotNo = targetSnapshotNo;
            changeCount = 0;
            status = DraftStatus.EDITABLE;
            onEvent.accept("BASELINE:" + targetSnapshotNo);
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

        DraftStateSnapshot snapshot() {
            return new DraftStateSnapshot(baseSnapshotNo, revision, status, null, changeCount);
        }
    }

    /** 草稿差异查询假仓储。 */
    static final class FakeDraftChangeQueryRepository implements DraftChangeQueryRepository {
        final List<DraftChangeRow> rows = new ArrayList<>();
        long deletedAll;
        final List<String> deletes = new ArrayList<>();
        private final List<String> calls;

        FakeDraftChangeQueryRepository(List<String> calls) {
            this.calls = calls;
        }

        DraftChangeRow add(String entityType, String entityName, String changeType, long version) {
            DraftChangeRow row = new DraftChangeRow(UUID.randomUUID(), entityType,
                    UUID.randomUUID(), entityName, changeType, List.of(), "admin", version,
                    5, OffsetDateTime.now(), OffsetDateTime.now());
            rows.add(row);
            return row;
        }

        @Override
        public List<DraftChangeRow> list(Connection connection, DraftChangeFilter filter,
                                         String sortExpression, int limit, long offset) {
            calls.add("list-changes");
            return List.copyOf(rows);
        }

        @Override
        public long count(Connection connection, DraftChangeFilter filter) {
            return rows.size();
        }

        @Override
        public DraftChangeSummaryCounts summary(Connection connection) {
            long create = rows.stream().filter(row -> row.changeType().equals("CREATE")).count();
            long update = rows.stream().filter(row -> row.changeType().equals("UPDATE")).count();
            long enable = rows.stream().filter(row -> row.changeType().equals("ENABLE")).count();
            long disable = rows.stream().filter(row -> row.changeType().equals("DISABLE")).count();
            long delete = rows.stream().filter(row -> row.changeType().equals("DELETE")).count();
            return new DraftChangeSummaryCounts(rows.size(), create, update, enable, disable, delete);
        }

        @Override
        public Map<String, Long> countByEntityType(Connection connection) {
            Map<String, Long> counts = new LinkedHashMap<>();
            rows.forEach(row -> counts.merge(row.entityType(), 1L, Long::sum));
            return counts;
        }

        @Override
        public Map<String, Map<String, Long>> countByEntityTypeAndChangeType(Connection connection) {
            Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
            rows.forEach(row -> counts
                    .computeIfAbsent(row.entityType(), key -> new LinkedHashMap<>())
                    .merge(row.changeType(), 1L, Long::sum));
            return counts;
        }

        @Override
        public Optional<ModifiedRange> modifiedRange(Connection connection) {
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ModifiedRange(rows.get(0).updatedAt(), rows.get(rows.size() - 1).updatedAt()));
        }

        @Override
        public Optional<DraftChangeRow> find(Connection connection, String entityType, UUID entityId) {
            return rows.stream()
                    .filter(row -> row.entityType().equals(entityType) && row.entityId().equals(entityId))
                    .findFirst();
        }

        @Override
        public int delete(Connection connection, String entityType, UUID entityId) {
            calls.add("delete-change");
            deletes.add(entityType + ":" + entityId);
            rows.removeIf(row -> row.entityType().equals(entityType) && row.entityId().equals(entityId));
            return 1;
        }

        @Override
        public long deleteAll(Connection connection) {
            calls.add("delete-all-changes");
            long count = rows.size();
            deletedAll += count;
            rows.clear();
            return count;
        }
    }

    /** 快照假仓储。 */
    static final class FakeSnapshotRepository implements com.lightai.storage.publish.ConfigSnapshotRepository {
        final Map<Long, ConfigSnapshotRecord> snapshots = new HashMap<>();
        long nextNo = 1;
        final List<String> events = new ArrayList<>();
        private final List<String> calls;

        FakeSnapshotRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public long nextSnapshotNo(Connection connection) {
            return nextNo;
        }

        @Override
        public void insert(Connection connection, ConfigSnapshotRecord record) {
            calls.add("insert-snapshot");
            snapshots.put(record.snapshotNo(), record);
        }

        @Override
        public Optional<ConfigSnapshotRecord> find(Connection connection, long snapshotNo) {
            return Optional.ofNullable(snapshots.get(snapshotNo));
        }

        @Override
        public Optional<ConfigSnapshotRecord> findActive(Connection connection) {
            return snapshots.values().stream()
                    .filter(snapshot -> ConfigSnapshotRecord.STATUS_ACTIVE.equals(snapshot.status()))
                    .findFirst();
        }

        @Override
        public void activate(Connection connection, long targetSnapshotNo) {
            calls.add("activate-snapshot");
            events.add("ACTIVATE:" + targetSnapshotNo);
            snapshots.values().stream()
                    .filter(snapshot -> ConfigSnapshotRecord.STATUS_ACTIVE.equals(snapshot.status()))
                    .forEach(snapshot -> snapshots.put(snapshot.snapshotNo(),
                            superseded(snapshot)));
            ConfigSnapshotRecord target = snapshots.get(targetSnapshotNo);
            snapshots.put(targetSnapshotNo, new ConfigSnapshotRecord(target.snapshotNo(),
                    target.schemaVersion(), ConfigSnapshotRecord.STATUS_ACTIVE, target.contentJson(),
                    target.contentChecksum(), target.contentSummaryJson(), OffsetDateTime.now(),
                    target.createdBy(), target.createdAt(), OffsetDateTime.now()));
        }

        @Override
        public void transitionStatus(Connection connection, long snapshotNo, String newStatus) {
            calls.add("snapshot-status:" + newStatus);
            events.add("STATUS:" + snapshotNo + ":" + newStatus);
            ConfigSnapshotRecord current = snapshots.get(snapshotNo);
            snapshots.put(snapshotNo, new ConfigSnapshotRecord(current.snapshotNo(),
                    current.schemaVersion(), newStatus, current.contentJson(),
                    current.contentChecksum(), current.contentSummaryJson(), current.activatedAt(),
                    current.createdBy(), current.createdAt(), OffsetDateTime.now()));
        }

        private static ConfigSnapshotRecord superseded(ConfigSnapshotRecord snapshot) {
            return new ConfigSnapshotRecord(snapshot.snapshotNo(), snapshot.schemaVersion(),
                    ConfigSnapshotRecord.STATUS_SUPERSEDED, snapshot.contentJson(),
                    snapshot.contentChecksum(), snapshot.contentSummaryJson(), snapshot.activatedAt(),
                    snapshot.createdBy(), snapshot.createdAt(), OffsetDateTime.now());
        }
    }

    /** 快照内容假仓储：固定内容树，可注入恢复失败。 */
    static final class FakeSnapshotContentRepository implements SnapshotContentRepository {
        Map<String, Object> content = new LinkedHashMap<>();
        boolean failRestore;
        final List<String> deleted = new ArrayList<>();
        final List<String> restored = new ArrayList<>();
        private final List<String> calls;

        FakeSnapshotContentRepository(List<String> calls) {
            this.calls = calls;
        }

        String canonical() {
            return canonicalJson(content);
        }

        @Override
        public Map<String, Object> assemble(Connection connection, String timezone) {
            calls.add("assemble");
            Map<String, Object> copy = new LinkedHashMap<>(content);
            Map<String, Object> runtime = new LinkedHashMap<>();
            runtime.put("timezone", timezone);
            copy.put("runtime_config", runtime);
            return copy;
        }

        @Override
        public String canonicalJson(Map<String, Object> contentTree) {
            try {
                return com.lightai.client.json.ProtocolJson.protocol().writeValueAsString(contentTree);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Map<String, Long> summarize(Map<String, Object> contentTree) {
            Map<String, Long> counts = new LinkedHashMap<>();
            contentTree.forEach((key, value) -> {
                if (value instanceof List<?> rows) {
                    counts.put(key, (long) rows.size());
                }
            });
            return counts;
        }

        @Override
        public List<RestoredEntity> restore(Connection connection, Map<String, Object> contentTree) {
            if (failRestore) {
                throw new IllegalStateException("基线恢复失败");
            }
            calls.add("restore-content");
            List<RestoredEntity> result = new ArrayList<>();
            contentTree.forEach((key, value) -> {
                if (value instanceof List<?> rows) {
                    rows.forEach(row -> {
                        if (row instanceof Map<?, ?> typed && typed.get("id") != null) {
                            result.add(new RestoredEntity(key, String.valueOf(typed.get("id"))));
                        }
                    });
                }
            });
            restored.addAll(result.stream().map(RestoredEntity::entityId).toList());
            return result;
        }

        @Override
        public int deleteDraftObject(Connection connection, String entityType, String entityId) {
            calls.add("delete-object");
            deleted.add(entityType + ":" + entityId);
            return 1;
        }

        @Override
        public int restoreUndelete(Connection connection, String entityType, String entityId) {
            calls.add("undelete-object");
            restored.add(entityId);
            return 1;
        }

        @Override
        public String contentKeyOf(String entityType) {
            return switch (entityType) {
                case "provider" -> "providers";
                case "credential_pool" -> "credential_pools";
                case "credential" -> "credentials";
                case "provider_model" -> "provider_models";
                case "model_alias" -> "model_aliases";
                case "route_candidate" -> "route_candidates";
                case "limit_policy" -> "limit_policies";
                case "reliability_policy" -> "reliability_policies";
                default -> throw new IllegalStateException("未知配置实体类型：" + entityType);
            };
        }
    }

    /** 校验假仓储。 */
    static final class FakeValidationRepository implements ConfigValidationRepository {
        ConfigValidationRecord lastRecord;
        final List<ConfigValidationIssueRecord> lastIssues = new ArrayList<>();
        final Set<UUID> used = new java.util.HashSet<>();
        private final List<String> calls;

        FakeValidationRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void insert(Connection connection, ConfigValidationRecord record,
                           List<ConfigValidationIssueRecord> issues) {
            calls.add("insert-validation");
            lastRecord = record;
            lastIssues.clear();
            lastIssues.addAll(issues);
        }

        @Override
        public Optional<ValidationWithIssues> find(Connection connection, UUID validationId) {
            if (lastRecord == null || !lastRecord.validationId().equals(validationId)) {
                return Optional.empty();
            }
            return Optional.of(new ValidationWithIssues(lastRecord, List.copyOf(lastIssues)));
        }

        @Override
        public void markUsed(Connection connection, UUID validationId, UUID publishId) {
            calls.add("mark-used");
            used.add(validationId);
            lastRecord = withUsed(lastRecord, publishId);
        }

        @Override
        public void sweepExpired(Connection connection, OffsetDateTime now) {
            calls.add("sweep-expired");
        }

        private static ConfigValidationRecord withUsed(ConfigValidationRecord record, UUID publishId) {
            return new ConfigValidationRecord(record.validationId(), record.baseSnapshotNo(),
                    record.targetSnapshotNo(), record.draftRevision(), record.contentChecksum(),
                    record.status(), record.errorCount(), record.warningCount(), record.validatedAt(),
                    record.expiresAt(), record.validatedBy(), publishId, record.changeSummaryJson(),
                    record.affectedAliasIds(), record.targetInstancesJson());
        }
    }

    /** 发布记录假仓储。 */
    static final class FakePublishRecordRepository implements PublishRecordRepository {
        final Map<UUID, PublishRecordRecord> records = new LinkedHashMap<>();
        private final List<String> calls;

        FakePublishRecordRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void insert(Connection connection, PublishRecordRecord record) {
            calls.add("insert-publish");
            records.put(record.id(), record);
        }

        @Override
        public void updateOutcome(Connection connection, UUID id, String status,
                                  OffsetDateTime completedAt, OffsetDateTime convergedAt,
                                  Long durationMs, String errorCode, String errorSummary) {
            calls.add("publish-outcome:" + status);
            PublishRecordRecord current = records.get(id);
            records.put(id, new PublishRecordRecord(current.id(), current.validationId(),
                    current.fromSnapshotNo(), current.targetSnapshotNo(), current.draftRevision(),
                    status, current.publishedBy(), current.publishNote(),
                    current.acknowledgedWarningIds(), current.targetInstanceIds(),
                    current.completedAt() != null ? current.completedAt() : completedAt,
                    convergedAt, current.durationMs() != null ? current.durationMs() : durationMs,
                    errorCode, errorSummary, current.createdAt(), OffsetDateTime.now()));
        }

        @Override
        public Optional<PublishRecordRecord> find(Connection connection, UUID id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public Optional<PublishRecordRecord> findByValidation(Connection connection, UUID validationId) {
            return records.values().stream()
                    .filter(record -> record.validationId().equals(validationId))
                    .findFirst();
        }

        @Override
        public List<PublishRecordRecord> list(Connection connection, PublishRecordFilter filter,
                                              String sortExpression, int limit, long offset) {
            return List.copyOf(records.values());
        }

        @Override
        public long count(Connection connection, PublishRecordFilter filter) {
            return records.size();
        }

        @Override
        public List<PublishRecordRecord> listUnfinished(Connection connection) {
            return records.values().stream()
                    .filter(record -> record.status().equals(PublishRecordRecord.STATUS_PREPARING)
                            || record.status().equals(PublishRecordRecord.STATUS_ACTIVATING))
                    .toList();
        }
    }

    /** 实例结果假仓储。 */
    static final class FakeInstanceResultRepository implements PublishInstanceResultRepository {
        final Map<UUID, List<PublishInstanceResultRecord>> byPublish = new LinkedHashMap<>();
        private final List<String> calls;

        FakeInstanceResultRepository(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void insertPending(Connection connection, UUID publishId, long fromSnapshotNo,
                                  long targetSnapshotNo, List<UUID> instanceIds) {
            calls.add("insert-pending");
            List<PublishInstanceResultRecord> results = new ArrayList<>();
            for (UUID instanceId : instanceIds) {
                results.add(new PublishInstanceResultRecord(UUID.randomUUID(), publishId, instanceId,
                        fromSnapshotNo, targetSnapshotNo, PublishInstanceResultRecord.STATUS_PENDING,
                        0, null, null, null, null, OffsetDateTime.now(),
                        "STANDALONE_SERVER", "1.0.0-test", List.of("1"), List.of("OPENAI")));
            }
            byPublish.put(publishId, results);
        }

        @Override
        public void applyReport(Connection connection, UUID publishId, UUID instanceId, String status,
                                OffsetDateTime reportedAt, int retryCount, Long loadDurationMs,
                                String errorCode, String errorSummary) {
            calls.add("apply-report:" + status);
            update(publishId, instanceId, record -> new PublishInstanceResultRecord(record.id(),
                    record.publishId(), record.instanceId(), record.fromSnapshotNo(),
                    record.targetSnapshotNo(), status, retryCount, loadDurationMs, reportedAt,
                    errorCode, errorSummary, OffsetDateTime.now(), record.runtimeMode(),
                    record.runtimeVersion(), record.supportedSchemaVersions(),
                    record.loadedAdapterTypes()));
        }

        @Override
        public Optional<PublishInstanceResultRecord> find(Connection connection, UUID publishId,
                                                          UUID instanceId) {
            return byPublish.getOrDefault(publishId, List.of()).stream()
                    .filter(record -> record.instanceId().equals(instanceId))
                    .findFirst();
        }

        @Override
        public List<PublishInstanceResultRecord> listByPublish(Connection connection, UUID publishId) {
            return List.copyOf(byPublish.getOrDefault(publishId, List.of()));
        }

        @Override
        public void markAllStatus(Connection connection, UUID publishId, String status) {
            calls.add("mark-all:" + status);
            byPublish.getOrDefault(publishId, List.of()).forEach(record -> update(publishId,
                    record.instanceId(), current -> new PublishInstanceResultRecord(current.id(),
                            current.publishId(), current.instanceId(), current.fromSnapshotNo(),
                            current.targetSnapshotNo(), status, current.retryCount(),
                            current.loadDurationMs(), current.reportedAt(), current.errorCode(),
                            current.errorSummary(), OffsetDateTime.now(), current.runtimeMode(),
                            current.runtimeVersion(), current.supportedSchemaVersions(),
                            current.loadedAdapterTypes())));
        }

        private void update(UUID publishId, UUID instanceId,
                            java.util.function.UnaryOperator<PublishInstanceResultRecord> operator) {
            List<PublishInstanceResultRecord> results = byPublish.get(publishId);
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).instanceId().equals(instanceId)) {
                    results.set(i, operator.apply(results.get(i)));
                }
            }
        }
    }

    /** 运行实例假仓储。 */
    static final class FakeRuntimeInstanceRepository implements RuntimeInstanceRepository {
        final List<RuntimeInstanceRecord> instances = new ArrayList<>();
        final List<RuntimeInstanceRecord> heartbeats = new ArrayList<>();
        int sweptStale;
        private final List<String> calls;

        FakeRuntimeInstanceRepository(List<String> calls) {
            this.calls = calls;
        }

        void online(UUID instanceId) {
            instances.add(new RuntimeInstanceRecord(instanceId, "STANDALONE_SERVER", "1.0.0-test",
                    "app", null, List.of("1"), List.of("OPENAI"), 0, true, "ONLINE",
                    OffsetDateTime.now(), null, null, OffsetDateTime.now()));
        }

        @Override
        public void upsertHeartbeat(Connection connection, RuntimeInstanceRecord record) {
            calls.add("upsert-heartbeat");
            heartbeats.add(record);
        }

        @Override
        public int sweepStale(Connection connection, int staleSeconds) {
            calls.add("sweep-stale");
            sweptStale = staleSeconds;
            return 0;
        }

        @Override
        public List<RuntimeInstanceRecord> findOnline(Connection connection) {
            calls.add("find-online");
            return instances.stream().filter(record -> "ONLINE".equals(record.status())).toList();
        }

        @Override
        public List<RuntimeInstanceRecord> list(Connection connection, RuntimeInstanceFilter filter,
                                                String sortExpression, int limit, long offset) {
            return List.copyOf(instances);
        }

        @Override
        public long count(Connection connection, RuntimeInstanceFilter filter) {
            return instances.size();
        }

        @Override
        public Optional<RuntimeInstanceRecord> find(Connection connection, UUID instanceId) {
            return instances.stream()
                    .filter(record -> record.instanceId().equals(instanceId)).findFirst();
        }
    }

    /** 撤销依赖假仓储。 */
    static final class FakeDependencyRepository implements com.lightai.storage.publish.DraftDependencyRepository {
        final Map<String, List<Blocker>> blockers = new HashMap<>();
        private final List<String> calls;

        FakeDependencyRepository(List<String> calls) {
            this.calls = calls;
        }

        void block(String entityType, String entityName) {
            blockers.put(entityType, List.of(new Blocker("route_candidate",
                    UUID.randomUUID().toString(), entityName)));
        }

        @Override
        public List<Blocker> findCreateBlockers(Connection connection, String entityType, UUID entityId) {
            calls.add("find-blockers");
            return blockers.getOrDefault(entityType, List.of());
        }
    }

    /** 审计记录器。 */
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

    /** 审计失败监听收集器。 */
    static final class FailureCollector implements com.lightai.admin.audit.AuditFailureListener {
        final List<AuditRecord> failures = new ArrayList<>();

        @Override
        public void onAuditWriteFailure(AuditRecord record, Exception cause) {
            failures.add(record);
        }
    }

    private PublishTestSupport() {
    }
}
