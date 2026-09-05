package com.lightai.storage.alias;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Route Candidate 仓储端口（DATABASE_PLAN §7）。写方法需在配置写事务内调用。 */
public interface RouteCandidateRepository {

    Optional<RouteCandidateRecord> find(Connection connection, UUID id);

    Optional<Long> findAliveVersion(Connection connection, UUID id);

    boolean existsAliveByTriple(Connection connection, UUID aliasId, UUID providerModelId, UUID credentialPoolId);

    void insert(Connection connection, RouteCandidateRecord record);

    /** 编辑候选：模型不可换，池/优先级/权重/启停可改。 */
    void update(Connection connection, RouteCandidateRecord record);

    /** 原子重排单行写入：仅更新 priority 与 version，weight 保持原值。 */
    void updatePriority(Connection connection, UUID id, int priority, long newVersion);

    List<RouteCandidateRecord> listByAlias(Connection connection, UUID aliasId, String orderSql);

    List<RouteCandidateRecord> findAliveByModelIds(Connection connection, List<UUID> modelIds);

    List<RouteCandidateRecord> findAliveByPoolIds(Connection connection, List<UUID> poolIds);
}
