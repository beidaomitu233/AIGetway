package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 应用变更影响预览（FE-P20 补充契约）。预览不是写入许可，最终命令仍重验版本、范围与占用。
 * affected_key_ids 最多 50 个；计数为十进制整数字符串；blockers 只含 code/message。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationImpactView(
        String applicationId,
        String version,
        String snapshotNo,
        OffsetDateTime generatedAt,
        String affectedKeyCount,
        List<String> affectedKeyIds,
        boolean hasMoreKeys,
        String requests24h,
        String runningRequests,
        List<Blocker> blockers) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Blocker(String code, String message) {
    }
}
