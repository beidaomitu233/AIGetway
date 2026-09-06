package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 实例心跳（POST /internal/runtime-instances/heartbeat，BE-041）。
 * 身份取实例认证主体；body 中 instance_id 必须与认证身份一致。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RuntimeInstanceHeartbeat(
        String instanceId,
        String runtimeMode,
        String runtimeVersion,
        String application,
        String zone,
        List<String> supportedSchemaVersions,
        List<String> loadedAdapterTypes,
        long activeSnapshotNo,
        boolean acceptingRequests,
        OffsetDateTime reportedAt) {

    public RuntimeInstanceHeartbeat {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instance_id 必填");
        }
        if (runtimeMode == null || runtimeMode.isBlank()) {
            throw new IllegalArgumentException("runtime_mode 必填");
        }
        supportedSchemaVersions = supportedSchemaVersions == null ? List.of() : List.copyOf(supportedSchemaVersions);
        loadedAdapterTypes = loadedAdapterTypes == null ? List.of() : List.copyOf(loadedAdapterTypes);
    }
}
