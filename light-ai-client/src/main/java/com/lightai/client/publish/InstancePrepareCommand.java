package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 实例准备命令（BACKEND_PLAN 2 协议字典）：服务端生成，实例据此构建独立内存配置。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InstancePrepareCommand(
        String publishId,
        long snapshotNo,
        String contentChecksum,
        int schemaVersion,
        OffsetDateTime deadlineAt) {
}
