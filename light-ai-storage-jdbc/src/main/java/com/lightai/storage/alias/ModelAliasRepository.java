package com.lightai.storage.alias;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Model Alias 仓储端口（DATABASE_PLAN §6）。写方法需在配置写事务内调用。 */
public interface ModelAliasRepository {

    Optional<ModelAliasRecord> find(Connection connection, UUID id);

    Optional<Long> findAliveVersion(Connection connection, UUID id);

    boolean existsAliveByAlias(Connection connection, String alias);

    void insert(Connection connection, ModelAliasRecord record);

    void update(Connection connection, ModelAliasRecord record);

    List<ModelAliasRecord> list(Connection connection, String filterSql, List<Object> filterValues,
                                String orderSql, long offset, int limit);

    long count(Connection connection, String filterSql, List<Object> filterValues);
}
