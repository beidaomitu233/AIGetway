package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 快照摘要（GET /admin/config/snapshots/{snapshotNo}/summary）。
 * 不含 content（4.5.6.1）；config_counts 为对象数量安全摘要。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConfigSnapshotSummaryView(
        long snapshotNo,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime activatedAt,
        String contentChecksum,
        Map<String, Long> configCounts) {
}
