package com.lightai.storage.publish;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * runtime_instance 仓储端口（BE-041）。
 * 心跳 upsert 幂等；status 由 accepting_requests 与失联时长推导维护。
 */
public interface RuntimeInstanceRepository {

    void upsertHeartbeat(Connection connection, RuntimeInstanceRecord record);

    int sweepStale(Connection connection, int staleSeconds);

    List<RuntimeInstanceRecord> findOnline(Connection connection);

    List<RuntimeInstanceRecord> list(Connection connection, RuntimeInstanceFilter filter,
                                     String sortExpression, int limit, long offset);

    long count(Connection connection, RuntimeInstanceFilter filter);

    Optional<RuntimeInstanceRecord> find(Connection connection, UUID instanceId);

    /** 实例列表筛选（GET /admin/runtime-instances）。 */
    record RuntimeInstanceFilter(Set<String> statuses, String runtimeMode, String application) {

        public RuntimeInstanceFilter {
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        }
    }
}
