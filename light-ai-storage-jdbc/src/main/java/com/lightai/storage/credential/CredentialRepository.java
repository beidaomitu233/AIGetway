package com.lightai.storage.credential;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Credential 仓储端口（DATABASE_PLAN §3）。
 * 写方法需在配置写事务（DraftWriteService）内调用；活行过滤 deleted_at IS NULL。
 */
public interface CredentialRepository {

    Optional<CredentialRecord> find(Connection connection, UUID id);

    Optional<Long> findAliveVersion(Connection connection, UUID id);

    boolean existsAliveByName(Connection connection, UUID poolId, String name);

    void insert(Connection connection, CredentialRecord record);

    /** 全字段草稿更新（含 version 与 updated_at，由服务给出新值）。 */
    void update(Connection connection, CredentialRecord record);

    /** 池下活行分页列表（含 credential_secret.masked_value 组合列）；filterValues 顺序与 filterSql 占位一一对应。 */
    List<CredentialRow> listByPool(Connection connection, UUID poolId, String filterSql,
                                   List<Object> filterValues, String orderSql, long offset, int limit);

    /** 组合行：凭证草稿 + 脱敏值（LEFT JOIN credential_secret）。 */
    record CredentialRow(CredentialRecord record, String maskedValue) {
    }

    long countByPool(Connection connection, UUID poolId, String filterSql, List<Object> filterValues);

    /** 按 ID 集合取活行（组合查询/引用校验用）。 */
    List<CredentialRecord> findAliveByIds(Connection connection, List<UUID> ids);
}
