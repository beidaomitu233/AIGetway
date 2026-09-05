package com.lightai.storage.credential;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * credential_secret 受保护仓储端口（DATABASE_PLAN §4）。
 * 仅凭证写入/轮换与运行期 Secret 解析服务可调用；读取方不得将密文进入日志。
 */
public interface SecretRepository {

    Optional<SecretRecord> find(Connection connection, UUID credentialId);

    /** 一凭证一条：不存在插入、存在覆盖（轮换）。需在业务事务内调用。 */
    void upsert(Connection connection, SecretRecord record);
}
