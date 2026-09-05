package com.lightai.storage.check;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** provider_check_record 仓储端口（DATABASE_PLAN §12）。检测记录为运行数据，不修改配置 version。 */
public interface CheckRecordRepository {

    void insert(Connection connection, CheckRecord record);

    Optional<CheckRecord> find(Connection connection, UUID id);

    /** 目标最近检测记录，按 started_at 倒序（详情页最近 10 条）。 */
    List<CheckRecord> findLatestByTarget(Connection connection, UUID targetId, int limit);
}
