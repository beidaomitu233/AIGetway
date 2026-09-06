package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 发布详情（前端 config.ts PublishRecordDetail 契约）。
 * completed_at 保留首轮结果，converged_at 记录最终收敛（4.5.2.4）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublishRecordDetailView(
        String id,
        long snapshotNo,
        long fromSnapshotNo,
        String status,
        String publishedByName,
        String publishNote,
        OffsetDateTime publishedAt,
        OffsetDateTime completedAt,
        Long durationMs,
        long targetSnapshotNo,
        long draftRevision,
        String contentChecksum,
        String changeSummary,
        List<String> affectedAliasIds,
        List<String> acknowledgedWarningIds,
        List<PublishInstanceResultView> instanceResults,
        OffsetDateTime firstRoundCompletedAt,
        OffsetDateTime convergedAt) {

    public PublishRecordDetailView {
        affectedAliasIds = affectedAliasIds == null ? List.of() : List.copyOf(affectedAliasIds);
        acknowledgedWarningIds = acknowledgedWarningIds == null ? List.of() : List.copyOf(acknowledgedWarningIds);
        instanceResults = instanceResults == null ? List.of() : List.copyOf(instanceResults);
    }
}
