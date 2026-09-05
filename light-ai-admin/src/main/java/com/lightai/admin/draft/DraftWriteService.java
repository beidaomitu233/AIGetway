package com.lightai.admin.draft;

import com.lightai.admin.audit.AuditRedactor;
import com.lightai.admin.audit.AuditService;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.draft.DraftChangeRecord;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.draft.DraftStateSnapshot;
import com.lightai.storage.draft.DraftStatus;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 配置写事务服务（BE-006）。
 * 顺序固定：锁 ConfigDraftState → PUBLISHING 拒绝 → 实体 version 比对 →
 * 实体写入回调 → 草稿差异 upsert → draft_revision+1 与 change_count 维护 →
 * 成功审计同事务提交。任一步失败：业务整体回滚，再以独立事务写失败审计，
 * request_id 与成功审计可互查；失败不递增 draft_revision（DATABASE_PLAN DB-004）。
 */
public class DraftWriteService {

    private static final int MAX_ERROR_SUMMARY = 1000;

    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final DraftStateRepository draftStateRepository;
    private final DraftChangeRepository draftChangeRepository;
    private final AuditService auditService;

    public DraftWriteService(DataSource dataSource, PlatformTransactionManager transactionManager,
                             DraftStateRepository draftStateRepository, DraftChangeRepository draftChangeRepository,
                             AuditService auditService) {
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.draftStateRepository = draftStateRepository;
        this.draftChangeRepository = draftChangeRepository;
        this.auditService = auditService;
    }

    public DraftWriteResult execute(DraftWriteCommand command) {
        try {
            return transaction.execute(status -> doInTransaction(command));
        } catch (LightAiException e) {
            recordFailureAudit(command, e.code().name(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            recordFailureAudit(command, ErrorCode.INTERNAL_ERROR.name(), e.getClass().getSimpleName());
            throw e;
        }
    }

    private DraftWriteResult doInTransaction(DraftWriteCommand command) {
        Connection connection = DataSourceUtils.getConnection(dataSource);

        DraftStateSnapshot locked = draftStateRepository.lock(connection);
        if (locked.status() == DraftStatus.PUBLISHING) {
            throw new LightAiException(ErrorCode.CONFIG_PUBLISH_IN_PROGRESS, "配置发布进行中，草稿暂不可写");
        }

        if (command.versionReader() != null) {
            Long currentVersion = command.versionReader().currentVersion(connection);
            if (currentVersion == null) {
                throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "配置对象不存在或已删除");
            }
            if (currentVersion != command.expectedVersion()) {
                throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT, "配置对象版本已变化，请刷新后重试",
                        null, command.requestId(), null, currentVersion, null, null);
            }
        }

        DraftEntityChange change = command.writer().write(connection);

        List<FieldChange> redacted = AuditRedactor.redact(change.changes());
        boolean inserted = draftChangeRepository.upsert(connection, new DraftChangeRecord(
                UUID.randomUUID(),
                change.entityType(),
                change.entityId(),
                change.entityName(),
                change.changeType(),
                redacted,
                command.operatorId(),
                change.entityVersion(),
                locked.draftRevision() + 1));
        int changeCountDelta = inserted ? 1 : 0;

        DraftStateSnapshot bumped = draftStateRepository.bumpRevision(connection, changeCountDelta);

        auditService.recordSuccess(connection, AuditRecord.succeeded(
                UUID.randomUUID(),
                command.requestId(),
                command.operatorId(),
                command.action(),
                change.entityType(),
                change.entityId().toString(),
                redacted,
                command.sourceMode(),
                command.sourceIpMasked()));

        return new DraftWriteResult(bumped.draftRevision(), change.entityVersion());
    }

    /**
     * 独立即时事务（Credential 纯轮换等，BACKEND_PLAN 第 3 节）：
     * 不取全局草稿锁、不产生草稿差异、不递增 draft_revision；
     * 实体 version 校验与成功审计同事务，失败审计独立。
     */
    public DraftWriteResult executeStandalone(DraftWriteCommand command) {
        try {
            return transaction.execute(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                if (command.versionReader() != null) {
                    Long currentVersion = command.versionReader().currentVersion(connection);
                    if (currentVersion == null) {
                        throw new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "配置对象不存在或已删除");
                    }
                    if (currentVersion != command.expectedVersion()) {
                        throw new LightAiException(ErrorCode.CONFIG_VERSION_CONFLICT,
                                "配置对象版本已变化，请刷新后重试",
                                null, command.requestId(), null, currentVersion, null, null);
                    }
                }
                DraftEntityChange change = command.writer().write(connection);
                auditService.recordSuccess(connection, AuditRecord.succeeded(
                        UUID.randomUUID(),
                        command.requestId(),
                        command.operatorId(),
                        command.action(),
                        change.entityType(),
                        change.entityId().toString(),
                        AuditRedactor.redact(change.changes()),
                        command.sourceMode(),
                        command.sourceIpMasked()));
                return new DraftWriteResult(-1, change.entityVersion());
            });
        } catch (LightAiException e) {
            recordFailureAudit(command, e.code().name(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            recordFailureAudit(command, ErrorCode.INTERNAL_ERROR.name(), e.getClass().getSimpleName());
            throw e;
        }
    }

    private void recordFailureAudit(DraftWriteCommand command, String errorCode, String safeSummary) {
        auditService.recordFailure(AuditRecord.failed(
                UUID.randomUUID(),
                command.requestId(),
                command.operatorId(),
                command.action(),
                command.entityType(),
                command.entityId(),
                errorCode,
                safeSummary(safeSummary),
                command.sourceMode(),
                command.sourceIpMasked()));
    }

    private static String safeSummary(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_ERROR_SUMMARY ? message : message.substring(0, MAX_ERROR_SUMMARY);
    }
}
