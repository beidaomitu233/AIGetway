package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 实例加载上报（POST /internal/publish-records/{publishId}/instances/{instanceId}/reports）。
 * 旧报告（reported_at 早于当前 updated_at）返回 INSTANCE_REPORT_CONFLICT；
 * 身份 instance_id 必须匹配路径。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InstanceLoadReport(
        long targetSnapshotNo,
        String status,
        OffsetDateTime reportedAt,
        int retryCount,
        Long loadDurationMs,
        String errorCode,
        String errorSummary) {

    public InstanceLoadReport {
        if (targetSnapshotNo < 0) {
            throw new IllegalArgumentException("target_snapshot_no 不能为负数");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status 必填");
        }
    }
}
