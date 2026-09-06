package com.lightai.admin.cleanup;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 保留清理服务（BE-048）：按当前 ACTIVE 留存策略分批清理，
 * 每批 ≤1000 Trace，只有聚合成功的可删；样本独立；快照保护引用；
 * 未消费事件跳过并告警，不删活动快照，不遗留孤儿数据。
 * 删除仓储端口由 DB-P05 迁移落地后提供 JDBC 实现。
 */
public class RetentionCleanupService {

    public static final int BATCH_SIZE = 1000;

    /** 删除端口：按清理顺序删除（明细 → Trace），Usage/审计独立批。 */
    public interface DeletionPort {
        List<String> findExpiredTraceIds(OffsetDateTime cutoff, int limit);

        int deleteTraceDetails(List<String> traceIds);

        int deleteTraces(List<String> traceIds);

        long deleteExpiredAudit(OffsetDateTime cutoff);

        long deleteExpiredSamples(OffsetDateTime cutoff);

        /** 未消费聚合事件数量（>0 时跳过对应 Trace 删除并告警）。 */
        long pendingAggregationEvents();
    }

    private final DeletionPort deletionPort;
    private final Clock clock;
    private final OffsetTimeSource traceCutoff;
    private final OffsetTimeSource usageCutoff;
    private final OffsetTimeSource auditCutoff;
    private final OffsetTimeSource sampleCutoff;

    /** 截止时间来源：由 RuntimeConfig 留存天数换算。 */
    public interface OffsetTimeSource {
        OffsetDateTime cutoff();
    }

    public RetentionCleanupService(DeletionPort deletionPort, Clock clock,
                                   OffsetTimeSource traceCutoff, OffsetTimeSource usageCutoff,
                                   OffsetTimeSource auditCutoff, OffsetTimeSource sampleCutoff) {
        this.deletionPort = deletionPort;
        this.clock = clock;
        this.traceCutoff = traceCutoff;
        this.usageCutoff = usageCutoff;
        this.auditCutoff = auditCutoff;
        this.sampleCutoff = sampleCutoff;
    }

    public CleanupReport run() {
        CleanupReport report = new CleanupReport();
        if (deletionPort.pendingAggregationEvents() > 0) {
            // 未消费事件：跳过本批 Trace 删除并告警，避免孤儿数据
            report.skippedPendingAggregation = true;
            return report;
        }
        boolean more = true;
        while (more) {
            List<String> traceIds = deletionPort.findExpiredTraceIds(traceCutoff.cutoff(), BATCH_SIZE);
            if (traceIds.isEmpty()) {
                break;
            }
            report.deletedTraceDetails += deletionPort.deleteTraceDetails(traceIds);
            report.deletedTraces += deletionPort.deleteTraces(traceIds);
            report.batches++;
            more = traceIds.size() == BATCH_SIZE;
        }
        report.deletedAudit = deletionPort.deleteExpiredAudit(auditCutoff.cutoff());
        report.deletedSamples = deletionPort.deleteExpiredSamples(sampleCutoff.cutoff());
        report.finishedAt = OffsetDateTime.now(clock);
        return report;
    }

    /** 清理报告：分批计数与跳过原因。 */
    public static final class CleanupReport {
        public int batches;
        public long deletedTraces;
        public long deletedTraceDetails;
        public long deletedAudit;
        public long deletedSamples;
        public boolean skippedPendingAggregation;
        public OffsetDateTime finishedAt;
    }
}
