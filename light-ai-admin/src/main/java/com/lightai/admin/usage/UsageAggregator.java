package com.lightai.admin.usage;

import com.lightai.admin.trace.TraceFinalizer;
import com.lightai.storage.trace.JdbcObservationConfigReader;
import com.lightai.storage.trace.JdbcTraceRepository;
import com.lightai.storage.trace.JdbcUsageAggregateRepository;
import com.lightai.storage.trace.JdbcUsageAggregationEventRepository;
import com.lightai.storage.trace.JdbcUsageAggregationEventRepository.ClaimedEvent;
import com.lightai.storage.trace.ObservationRows.TraceRow;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Usage 聚合处理器（BE-033；4.4.3.6 与 DATABASE_PLAN usage_aggregation_event）。
 * 原子取得一条 PENDING/到期 FAILED/租约过期 PROCESSING 事件并递增 lock_generation；
 * HOUR/DAY 聚合更新与事件 SUCCEEDED 同一事务提交；提交前行锁下核对 fencing 令牌，
 * 120 秒内被其他实例接管时放弃本次提交。失败时回滚全部聚合增量，
 * 事件按 1/2/4/8/16 分钟退避，之后固定 30 分钟重试；连续失败 10 次产生运行告警。
 */
public class UsageAggregator {

    private static final Logger log = LoggerFactory.getLogger(UsageAggregator.class);

    static final Duration LEASE = Duration.ofSeconds(120);
    private static final long[] BACKOFF_SECONDS = {60, 120, 240, 480, 960};
    private static final long STEADY_BACKOFF_SECONDS = 1800;
    private static final int ALERT_EVERY_FAILURES = 10;

    private final DataSource dataSource;
    private final JdbcTraceRepository traceRepository;
    private final JdbcUsageAggregateRepository aggregateRepository;
    private final JdbcUsageAggregationEventRepository eventRepository;
    private final JdbcObservationConfigReader configReader;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final String workerId;

    public UsageAggregator(DataSource dataSource, JdbcTraceRepository traceRepository,
                           JdbcUsageAggregateRepository aggregateRepository,
                           JdbcUsageAggregationEventRepository eventRepository,
                           JdbcObservationConfigReader configReader,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.dataSource = dataSource;
        this.traceRepository = traceRepository;
        this.aggregateRepository = aggregateRepository;
        this.eventRepository = eventRepository;
        this.configReader = configReader;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.workerId = hostId();
    }

    private static String hostId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 事件桶：claim 与处理结果在循环间传递。 */
    private record Claimed(ClaimedEvent event) {
    }

    /** 处理至多 maxEvents 条事件；返回处理条数。无可处理事件立即返回 0。 */
    public int processPending(int maxEvents) {
        int processed = 0;
        while (processed < maxEvents) {
            Claimed claimed = claim();
            if (claimed == null) {
                break;
            }
            processOne(claimed.event());
            processed++;
        }
        return processed;
    }

    private Claimed claim() {
        return transactionTemplate.execute(status -> {
            try (Connection connection = dataSource.getConnection()) {
                Optional<ClaimedEvent> event = eventRepository.claimNext(connection, workerId);
                return event.map(Claimed::new).orElse(null);
            } catch (SQLException e) {
                throw new IllegalStateException("聚合事件取得失败", e);
            }
        });
    }

    private void processOne(ClaimedEvent event) {
        Boolean applied;
        try {
            applied = transactionTemplate.execute(status -> {
                try (Connection connection = dataSource.getConnection()) {
                    // fencing：120 秒租约被接管时放弃提交，旧 worker 不产生任何增量
                    if (!eventRepository.lockAndVerify(connection, event.id(),
                            event.lockGeneration())) {
                        return false;
                    }
                    JdbcTraceRepository.TerminalTrace terminal =
                            traceRepository.findTerminalWithAttempts(connection, event.traceId());
                    TraceRow trace = terminal.trace();
                    if (!TraceFinalizer.TERMINAL_STATUSES.contains(trace.status())) {
                        throw new IllegalStateException("事件对应Trace非终态：" + event.traceId());
                    }
                    List<com.lightai.storage.trace.JdbcUsageAggregateRepository.Contribution>
                            contributions = ContributionCalculator.compute(trace,
                            terminal.attempts(), zone(connection));
                    for (var contribution : contributions) {
                        aggregateRepository.upsertContribution(connection, contribution);
                    }
                    eventRepository.markSucceeded(connection, event.id());
                    return true;
                } catch (SQLException e) {
                    throw new IllegalStateException("聚合处理失败：" + event.traceId(), e);
                }
            });
        } catch (Exception e) {
            // 聚合事务已整体回滚：事件记录错误并退避重试，不返回同步成功
            markFailed(event, e);
            return;
        }
        if (!Boolean.TRUE.equals(applied)) {
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Usage聚合完成 trace_id={} generation={}", event.traceId(),
                    event.lockGeneration());
        }
    }

    /** 失败收尾：事件记录错误并按退避计划设置 next_retry_at；重放零增量由同事务回滚保证。 */
    void markFailed(ClaimedEvent event, Exception cause) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                try (Connection connection = dataSource.getConnection()) {
                    if (!eventRepository.lockAndVerify(connection, event.id(),
                            event.lockGeneration())) {
                        return;
                    }
                    int nextRetryCount = event.retryCount() + 1;
                    OffsetDateTime nextRetryAt = OffsetDateTime.now(clock)
                            .plusSeconds(backoffSeconds(event.retryCount()));
                    eventRepository.markFailed(connection, event.id(),
                            "USAGE_AGGREGATION_FAILED", safeSummary(cause), nextRetryAt);
                    if (nextRetryCount % ALERT_EVERY_FAILURES == 0) {
                        log.error("Usage聚合连续失败告警 trace_id={} retry_count={} "
                                        + "事件保留继续重试", event.traceId(), nextRetryCount);
                    } else {
                        log.warn("Usage聚合失败将退避重试 trace_id={} retry_count={}",
                                event.traceId(), nextRetryCount);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("聚合失败记录写入异常", e);
                }
            });
        } catch (Exception e) {
            log.warn("聚合失败记录自身异常 trace_id={}", event.traceId());
        }
    }

    static long backoffSeconds(int completedFailures) {
        if (completedFailures < BACKOFF_SECONDS.length) {
            return BACKOFF_SECONDS[completedFailures];
        }
        return STEADY_BACKOFF_SECONDS;
    }

    private static String safeSummary(Exception cause) {
        // 不回传异常细节，避免泄漏内部信息；仅类别与安全摘要
        return cause.getClass().getSimpleName();
    }

    private ZoneId zone(Connection connection) {
        String timezone = configReader.read(connection)
                .map(JdbcObservationConfigReader.ObservationConfig::timezone)
                .orElse("UTC");
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}
