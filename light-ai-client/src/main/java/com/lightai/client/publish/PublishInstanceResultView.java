package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 发布实例结果（DATABASE_PLAN §34；前端 config.ts PublishInstanceResult 契约）。
 * FAILED/TIMED_OUT 可在后台重新加载后转 LOADED（4.5.2.5）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublishInstanceResultView(
        String instanceId,
        String runtimeMode,
        String runtimeVersion,
        java.util.List<String> supportedSchemaVersions,
        java.util.List<String> loadedAdapterTypes,
        long fromSnapshotNo,
        long targetSnapshotNo,
        String status,
        int retryCount,
        Long loadDurationMs,
        String errorCode,
        String errorSummary,
        OffsetDateTime updatedAt) {

    public PublishInstanceResultView {
        supportedSchemaVersions = supportedSchemaVersions == null ? java.util.List.of() : java.util.List.copyOf(supportedSchemaVersions);
        loadedAdapterTypes = loadedAdapterTypes == null ? java.util.List.of() : java.util.List.copyOf(loadedAdapterTypes);
    }
}
