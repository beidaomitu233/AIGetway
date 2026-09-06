package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运行实例（DATABASE_PLAN §35；前端 config.ts RuntimeInstance 契约）。
 * status 由心跳与 accepting_requests 推导存储（PROJECT_DOCUMENT 2.5.5）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RuntimeInstanceView(
        String instanceId,
        String runtimeMode,
        String runtimeVersion,
        String application,
        String zone,
        String status,
        boolean acceptingRequests,
        long activeSnapshotNo,
        List<String> supportedSchemaVersions,
        List<String> loadedAdapterTypes,
        OffsetDateTime lastHeartbeatAt) {

    public RuntimeInstanceView {
        supportedSchemaVersions = supportedSchemaVersions == null ? List.of() : List.copyOf(supportedSchemaVersions);
        loadedAdapterTypes = loadedAdapterTypes == null ? List.of() : List.copyOf(loadedAdapterTypes);
    }
}
