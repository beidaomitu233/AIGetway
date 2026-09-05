package com.lightai.admin.audit;

import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.audit.AuditRepository;
import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * 审计服务（BE-005）。
 * 成功审计：在调用方业务事务的当前连接内写入，随业务同事务提交或回滚；
 * 失败审计：业务回滚后以独立新事务写入，保留 request_id 关联。
 * 审计不可写：成功路径随业务回滚；失败路径告警且不伪造成功记录。
 */
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;
    private final DataSource dataSource;
    private final TransactionTemplate independentTransaction;
    private final AuditFailureListener failureListener;

    public AuditService(AuditRepository auditRepository, DataSource dataSource,
                        PlatformTransactionManager transactionManager, AuditFailureListener failureListener) {
        this.auditRepository = auditRepository;
        this.dataSource = dataSource;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.independentTransaction = template;
        this.failureListener = failureListener;
    }

    /** 成功审计：与业务同一事务（connection 为业务事务当前连接）。 */
    public void recordSuccess(Connection connection, AuditRecord record) {
        auditRepository.insert(connection, record);
    }

    /** 失败审计：独立事务，业务回滚不影响其落库。 */
    public void recordFailure(AuditRecord record) {
        try {
            independentTransaction.executeWithoutResult(status ->
                    auditRepository.insert(DataSourceUtils.getConnection(dataSource), record));
        } catch (Exception cause) {
            // 失败审计也不可用：只能告警，不得伪造任何审计结果
            if (failureListener != null) {
                failureListener.onAuditWriteFailure(record, cause);
            } else {
                log.error("审计写入失败且无告警通道 request_id={} action={}",
                        record.requestId(), record.action());
            }
        }
    }
}
