package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 发布历史列表项（前端 config.ts PublishRecordListItem 契约）。
 * published_by_name 暂取 published_by（C-025 同口径）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublishRecordListItemView(
        String id,
        long snapshotNo,
        long fromSnapshotNo,
        String status,
        String publishedByName,
        String publishNote,
        OffsetDateTime publishedAt,
        OffsetDateTime completedAt,
        Long durationMs) {
}
