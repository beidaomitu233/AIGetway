package com.lightai.admin.trace;

import com.lightai.storage.trace.JdbcTraceRepository;
import com.lightai.storage.trace.JdbcUsageAggregationEventRepository;
import java.sql.Connection;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trace 最终化（BE-033；4.4.3.6）。
 * Trace 进入最终状态后，在保存最终 Trace、最后 Attempt 与 Usage/Cost 结算的
 * 同一业务事务内写入唯一 UsageAggregationEvent（U(trace_id) 幂等）。
 * finalizeInTransaction 供 /v1 管道在终态事务内直接调用（BE-P05 合入后接线）；
 * finalizeTrace 为独立事务入口，用于恢复与补记场景。
 */
public class TraceFinalizer {

    public static final Set<String> TERMINAL_STATUSES = Set.of(
            "SUCCEEDED", "FAILED", "CANCELLED", "STREAM_INTERRUPTED");

    private final DataSource dataSource;
    private final JdbcTraceRepository traceRepository;
    private final JdbcUsageAggregationEventRepository eventRepository;
    private final TransactionTemplate transactionTemplate;

    public TraceFinalizer(DataSource dataSource, JdbcTraceRepository traceRepository,
                          JdbcUsageAggregationEventRepository eventRepository,
                          PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.traceRepository = traceRepository;
        this.eventRepository = eventRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 管道终态事务内调用：仅当 Trace 已终态时写入事件；事件已存在返回 false。 */
    public boolean finalizeInTransaction(Connection connection, String traceId) {
        String status = traceRepository.findByTraceId(connection, traceId)
                .orElseThrow(() -> new IllegalStateException("Trace不存在：" + traceId))
                .status();
        if (!TERMINAL_STATUSES.contains(status)) {
            throw new IllegalStateException("Trace未进入终态，不能写入聚合事件：" + traceId);
        }
        return eventRepository.insertIfAbsent(connection, traceId);
    }

    /** 独立事务入口：校验终态并写入事件；返回是否新建了事件。 */
    public boolean finalizeTrace(String traceId) {
        Boolean created = transactionTemplate.execute(status -> {
            try (Connection connection = dataSource.getConnection()) {
                return finalizeInTransaction(connection, traceId);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Trace最终化失败：" + traceId, e);
            }
        });
        return Boolean.TRUE.equals(created);
    }
}
