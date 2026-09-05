package com.lightai.storage.model;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Provider Model 仓储端口（DATABASE_PLAN §5）。写方法需在配置写事务内调用。 */
public interface ProviderModelRepository {

    Optional<ProviderModelRecord> find(Connection connection, UUID id);

    Optional<Long> findAliveVersion(Connection connection, UUID id);

    boolean existsAliveByModelId(Connection connection, UUID providerId, String modelId);

    boolean existsAliveByDisplayName(Connection connection, UUID providerId, String displayName);

    void insert(Connection connection, ProviderModelRecord record);

    void update(Connection connection, ProviderModelRecord record);

    /** 跨 Provider 查询（列表/导入 existing 标记）。 */
    List<ProviderModelRecord> list(Connection connection, String filterSql, List<Object> filterValues,
                                   String orderSql, long offset, int limit);

    long count(Connection connection, String filterSql, List<Object> filterValues);
}
