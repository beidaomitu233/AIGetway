package com.lightai.storage.publish;

import java.sql.Connection;
import java.util.Optional;

/**
 * config_snapshot 仓储端口（BE-040/042）。
 * 激活必须在发布激活事务内：旧 ACTIVE → SUPERSEDED、目标 → ACTIVE、指针更新。
 */
public interface ConfigSnapshotRepository {

    long nextSnapshotNo(Connection connection);

    void insert(Connection connection, ConfigSnapshotRecord record);

    Optional<ConfigSnapshotRecord> find(Connection connection, long snapshotNo);

    Optional<ConfigSnapshotRecord> findActive(Connection connection);

    void activate(Connection connection, long targetSnapshotNo);

    /** 回滚激活（BE-233）：把 SUPERSEDED 的历史快照重新置为 ACTIVE；单一活动版本由事务维护。 */
    void reactivate(Connection connection, long targetSnapshotNo);

    void transitionStatus(Connection connection, long snapshotNo, String newStatus);
}
