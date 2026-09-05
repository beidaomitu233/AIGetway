package com.lightai.storage.audit;

import java.sql.Connection;

/**
 * 审计仓储端口。实现不打印绑定值，不记录密钥、Token 与消息正文。
 */
public interface AuditRepository {

    /** 在当前事务连接内写入审计；成功审计与业务同事务提交。 */
    void insert(Connection connection, AuditRecord record);
}
