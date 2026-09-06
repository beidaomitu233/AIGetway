package com.lightai.storage.access;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access Credential 仓储端口（DATABASE_PLAN §36/§37）。
 * 即时实体：启用/停用/轮换/删除即时生效，不走草稿发布流程。
 */
public interface AccessCredentialRepository {

    Optional<AccessCredentialRecord> find(Connection connection, UUID id);

    /** 业务鉴权专用：按 HMAC 摘要查活行（U(token_hash)）；供 AccessTokenPort 实现。 */
    Optional<AccessCredentialRecord> findByTokenHash(Connection connection, byte[] tokenHash);

    boolean existsAliveByName(Connection connection, String name);

    void insert(Connection connection, AccessCredentialRecord record, List<UUID> allowedAliasIds);

    /** 编辑/启停/轮换/软删除共用全字段更新。 */
    void update(Connection connection, AccessCredentialRecord record, List<UUID> allowedAliasIds);

    /** Alias 白名单（空行集表示全部）；attachTo 供查询组合。 */
    List<UUID> aliasIdsOf(Connection connection, UUID credentialId);

    List<AccessCredentialRecord> list(Connection connection, String filterSql, List<Object> filterValues,
                                      String orderSql, long offset, int limit);

    long count(Connection connection, String filterSql, List<Object> filterValues);

    /** 记录活动摘要（last_used_at/ip 脱敏），鉴权成功路径调用。 */
    void touch(Connection connection, UUID id, OffsetDateTime usedAt, String maskedIp);
}
