package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 内部快照读取响应（GET /internal/config-snapshots/{snapshotNo}）。
 * content 为规范化白名单配置树，不含秘密（4.5.6.6）；仅授权实例可取。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConfigSnapshotContentView(
        long snapshotNo,
        int schemaVersion,
        String status,
        String contentChecksum,
        Object content,
        OffsetDateTime createdAt) {
}
