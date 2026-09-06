package com.lightai.storage.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * runtime_instance 行（DATABASE_PLAN §35）。
 * status 由服务端按心跳与 accepting_requests 维护（ONLINE/DRAINING/STALE/OFFLINE）。
 */
public record RuntimeInstanceRecord(
        UUID instanceId,
        String runtimeMode,
        String runtimeVersion,
        String application,
        String zone,
        List<String> supportedSchemaVersions,
        List<String> loadedAdapterTypes,
        long activeSnapshotNo,
        boolean acceptingRequests,
        String status,
        OffsetDateTime lastHeartbeatAt,
        String lastErrorCode,
        String lastErrorSummary,
        OffsetDateTime updatedAt) {

    public RuntimeInstanceRecord {
        supportedSchemaVersions = supportedSchemaVersions == null ? List.of() : List.copyOf(supportedSchemaVersions);
        loadedAdapterTypes = loadedAdapterTypes == null ? List.of() : List.copyOf(loadedAdapterTypes);
    }
}
