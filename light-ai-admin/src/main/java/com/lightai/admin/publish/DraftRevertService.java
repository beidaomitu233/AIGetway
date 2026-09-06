package com.lightai.admin.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lightai.admin.audit.AuditService;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.ConfigDraftState;
import com.lightai.client.publish.RevertAllDraftCommand;
import com.lightai.client.publish.RevertDraftCommand;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.draft.DraftChangeQueryRepository;
import com.lightai.storage.draft.DraftChangeRow;
import com.lightai.storage.draft.DraftPublishStateRepository;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.draft.DraftStateSnapshot;
import com.lightai.storage.draft.DraftStatus;
import com.lightai.storage.publish.ConfigSnapshotRecord;
import com.lightai.storage.publish.ConfigSnapshotRepository;
import com.lightai.storage.publish.DraftDependencyRepository;
import com.lightai.storage.publish.SnapshotContentRepository;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 草稿撤销服务（BE-038，4.5.1.5）。单项撤销不得级联修改其他草稿对象；
 * 全量撤销以 base_snapshot_no 为唯一目标、原子恢复，任一步失败全部回滚。
 * 秘密轮换存独立受保护表，不在撤销恢复范围内（4.5.1.4）。
 */
public class DraftRevertService {

    private static final int MAX_ERROR_SUMMARY = 1000;
    private static final Set<String> REVERTABLE_TYPES = Set.of(
            "provider", "credential_pool", "credential", "provider_model",
            "model_alias", "route_candidate", "limit_policy", "reliability_policy");

    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final DraftStateRepository draftStateRepository;
    private final DraftPublishStateRepository draftPublishStateRepository;
    private final DraftChangeQueryRepository draftChangeQueryRepository;
    private final ConfigSnapshotRepository snapshotRepository;
    private final SnapshotContentRepository snapshotContentRepository;
    private final DraftDependencyRepository dependencyRepository;
    private final AuditService auditService;
    private final String sourceMode;

    public DraftRevertService(DataSource dataSource, PlatformTransactionManager transactionManager,
                              DraftStateRepository draftStateRepository,
                              DraftPublishStateRepository draftPublishStateRepository,
                              DraftChangeQueryRepository draftChangeQueryRepository,
                              ConfigSnapshotRepository snapshotRepository,
                              SnapshotContentRepository snapshotContentRepository,
                              DraftDependencyRepository dependencyRepository,
                              AuditService auditService, String sourceMode) {
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.draftStateRepository = draftStateRepository;
        this.draftPublishStateRepository = draftPublishStateRepository;
        this.draftChangeQueryRepository = draftChangeQueryRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotContentRepository = snapshotContentRepository;
        this.dependencyRepository = dependencyRepository;
        this.auditService = auditService;
        this.sourceMode = sourceMode;
    }

    public ConfigDraftState revertOne(String requestId, String operatorId, String sourceIpMasked,
                                      String entityType, String entityId,
                                      RevertDraftCommand command) {
        if (!REVERTABLE_TYPES.contains(entityType)) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "不支持的撤销对象类型",
                    List.of(new com.lightai.client.error.FieldIssue("entityType", "INVALID",
                            "不支持的撤销对象类型：" + entityType)));
        }
        UUID id = parseId(entityId);
        try {
            ConfigDraftState state = transaction.execute(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                DraftStateSnapshot locked = draftStateRepository.lock(connection);
                if (locked.status() == DraftStatus.PUBLISHING) {
                    throw new LightAiException(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS, "配置发布进行中，草稿暂不可撤销");
                }
                if (locked.draftRevision() != command.draftRevision()) {
                    throw new LightAiException(ErrorCode.CONFIG_DRAFT_CHANGED, "草稿修订已变化，请刷新后重试");
                }
                DraftChangeRow change = draftChangeQueryRepository.find(connection, entityType, id)
                        .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "草稿差异不存在或已撤销"));
                if (change.entityVersion() != command.version()) {
                    throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "草稿对象版本已变化，请刷新后重试",
                            null, requestId, null, change.entityVersion(), null, null);
                }
                List<DraftDependencyRepository.Blocker> blockers =
                        dependencyRepository.findCreateBlockers(connection, entityType, id);
                if (!blockers.isEmpty()) {
                    throw new LightAiException(ErrorCode.DRAFT_REVERT_BLOCKED,
                            "其他新建草稿引用该对象，请先处理引用对象：" + blockers.get(0).entityName());
                }
                applyRevert(connection, entityType, id, change.changeType(), locked.baseSnapshotNo());
                draftChangeQueryRepository.delete(connection, entityType, id);
                draftStateRepository.bumpRevision(connection, -1);
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), requestId, operatorId, "REVERT",
                        entityType, id.toString(), List.of(FieldChange.changed(
                                "change_type", change.changeType(), null)),
                        sourceMode, sourceIpMasked));
                return readState(connection);
            });
            return state;
        } catch (LightAiException e) {
            recordFailure(requestId, operatorId, sourceMode, sourceIpMasked, "REVERT",
                    entityType, entityId, e);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(requestId, operatorId, sourceMode, sourceIpMasked, "REVERT",
                    entityType, entityId, e);
            throw e;
        }
    }

    public ConfigDraftState revertAll(String requestId, String operatorId, String sourceIpMasked,
                                      RevertAllDraftCommand command) {
        try {
            ConfigDraftState state = transaction.execute(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                DraftStateSnapshot locked = draftStateRepository.lock(connection);
                if (locked.status() == DraftStatus.PUBLISHING) {
                    throw new LightAiException(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS, "配置发布进行中，草稿暂不可撤销");
                }
                if (locked.draftRevision() != command.draftRevision()) {
                    throw new LightAiException(ErrorCode.CONFIG_DRAFT_CHANGED, "草稿修订已变化，请刷新后重试");
                }
                if (locked.changeCount() <= 0) {
                    throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "当前草稿无待撤销变更",
                            List.of(new com.lightai.client.error.FieldIssue("draft_revision", "INVALID",
                                    "当前草稿无待撤销变更")));
                }
                ConfigSnapshotRecord base = snapshotRepository.find(connection, locked.baseSnapshotNo())
                        .orElseThrow(() -> new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE,
                                "基线快照不可用，无法执行全部撤销"));
                Map<String, Object> content = parseContent(base.contentJson());
                List<SnapshotContentRepository.RestoredEntity> restored =
                        snapshotContentRepository.restore(connection, content);

                List<DraftChangeRow> allChanges = draftChangeQueryRepository.list(
                        connection, new DraftChangeQueryRepository.DraftChangeFilter(
                                null, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                                null, null),
                        "updated_at asc", Integer.MAX_VALUE, 0);
                int revertedObjects = 0;
                for (DraftChangeRow change : allChanges) {
                    if ("CREATE".equals(change.changeType())) {
                        snapshotContentRepository.deleteDraftObject(
                                connection, change.entityType(), change.entityId().toString());
                    }
                    auditService.recordSuccess(connection, AuditRecord.succeeded(
                            UUID.randomUUID(), requestId, operatorId, "REVERT",
                            change.entityType(), change.entityId().toString(), List.of(),
                            sourceMode, sourceIpMasked));
                    revertedObjects++;
                }
                draftChangeQueryRepository.deleteAll(connection);
                draftPublishStateRepository.activateBaseline(connection, locked.baseSnapshotNo());
                draftStateRepository.bumpRevision(connection, 0);
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(), requestId, operatorId, "REVERT_ALL",
                        "config_draft_state", null, List.of(FieldChange.changed(
                                "reverted_objects", String.valueOf(revertedObjects),
                                String.valueOf(restored.size()))),
                        sourceMode, sourceIpMasked));
                return readState(connection);
            });
            return state;
        } catch (LightAiException e) {
            recordFailure(requestId, operatorId, sourceMode, sourceIpMasked, "REVERT_ALL",
                    "config_draft_state", null, e);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(requestId, operatorId, sourceMode, sourceIpMasked, "REVERT_ALL",
                    "config_draft_state", null, e);
            throw e;
        }
    }

    /** 撤销语义：CREATE 删除草稿对象；DELETE 恢复活行；其余从基线快照覆盖恢复。 */
    private void applyRevert(Connection connection, String entityType, UUID id,
                             String changeType, long baseSnapshotNo) {
        if ("CREATE".equals(changeType)) {
            snapshotContentRepository.deleteDraftObject(connection, entityType, id.toString());
            return;
        }
        ConfigSnapshotRecord base = snapshotRepository.find(connection, baseSnapshotNo)
                .orElseThrow(() -> new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE,
                        "基线快照不可用，无法撤销"));
        Map<String, Object> content = parseContent(base.contentJson());
        if ("DELETE".equals(changeType)) {
            // 撤销删除：清除删除标记并恢复活动值（4.5.1.5）
            restoreObject(connection, content, entityType, id, true);
            return;
        }
        restoreObject(connection, content, entityType, id, false);
    }

    @SuppressWarnings("unchecked")
    private void restoreObject(Connection connection, Map<String, Object> content,
                               String entityType, UUID id, boolean restoreDeleted) {
        Object rowsObject = content.get(snapshotContentRepository.contentKeyOf(entityType));
        if (!(rowsObject instanceof List<?> rows)) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "基线快照缺少该对象类别");
        }
        for (Object rowObject : rows) {
            if (rowObject instanceof Map<?, ?> row
                    && id.toString().equals(String.valueOf(row.get("id")))) {
                snapshotContentRepository.restore(connection,
                        Map.of(snapshotContentRepository.contentKeyOf(entityType), List.of(row)));
                return;
            }
        }
        if (restoreDeleted) {
            // 基线中不存在而草稿标记删除：仅清除删除标记（对象在草稿中未发布创建过）
            snapshotContentRepository.restoreUndelete(connection, entityType, id.toString());
            return;
        }
        throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "基线快照中不存在该对象，无法恢复");
    }

    private ConfigDraftState readState(Connection connection) {
        DraftStateSnapshot snapshot = draftStateRepository.find(connection)
                .orElse(new DraftStateSnapshot(0, 0, DraftStatus.EDITABLE, null, 0));
        return new ConfigDraftState(snapshot.baseSnapshotNo(), snapshot.draftRevision(),
                snapshot.changeCount(), snapshot.status().name(), null, null);
    }

    private Map<String, Object> parseContent(String contentJson) {
        try {
            return com.lightai.client.json.ProtocolJson.protocol()
                    .readValue(contentJson, Map.class);
        } catch (JsonProcessingException e) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "基线快照内容无法解析");
        }
    }

    private void recordFailure(String requestId, String operatorId, String sourceMode,
                               String sourceIpMasked, String action, String entityType,
                               String entityId, Exception cause) {
        String code = cause instanceof LightAiException lightAi
                ? lightAi.code().name() : ErrorCode.INTERNAL_ERROR.name();
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        auditService.recordFailure(AuditRecord.failed(
                UUID.randomUUID(), requestId, operatorId, action, entityType, entityId,
                code, message.length() <= MAX_ERROR_SUMMARY ? message : message.substring(0, MAX_ERROR_SUMMARY),
                sourceMode, sourceIpMasked));
    }

    private static UUID parseId(String entityId) {
        try {
            return UUID.fromString(entityId);
        } catch (IllegalArgumentException e) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "entityId 不合法",
                    List.of(new com.lightai.client.error.FieldIssue("entityId", "INVALID",
                            "entityId 必须是 UUID")));
        }
    }
}
