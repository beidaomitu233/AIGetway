package com.lightai.admin.audit;

/**
 * 审计写入失败告警钩子：审计不可写时回滚业务并告警（BE-005 验收），
 * 不伪造成功记录。默认实现输出错误日志，部署方可替换为告警通道。
 */
public interface AuditFailureListener {

    void onAuditWriteFailure(com.lightai.storage.audit.AuditRecord record, Exception cause);
}
