package com.lightai.storage.check;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 批量检测任务仓储端口（DATABASE_PLAN §13/§14）。
 * 汇总计数字段由明细更新在同一事务内维护；任务不修改配置草稿。
 */
public interface BatchCheckJobRepository {

    void insert(Connection connection, BatchCheckJobRecord job, List<BatchCheckItemRecord> items);

    Optional<BatchCheckJobRecord> find(Connection connection, UUID id);

    List<BatchCheckItemRecord> listItems(Connection connection, UUID jobId);

    /** 任务汇总与状态更新（completed/success/failure/cancelled 由服务计算）。 */
    void updateSummary(Connection connection, BatchCheckJobRecord job);

    void updateItem(Connection connection, BatchCheckItemRecord item);

    /** 取消未开始项，返回取消数量；RUNNING 项不受影响。 */
    int cancelPendingItems(Connection connection, UUID jobId);
}
