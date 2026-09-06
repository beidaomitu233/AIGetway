package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 心跳响应（BACKEND_PLAN 2 协议字典）：prepare_command 与 activation_command
 * 互斥且可均空；实例优先完成准备与 READY 上报。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RuntimeHeartbeatResponse(
        OffsetDateTime serverTime,
        long activeSnapshotNo,
        InstancePrepareCommand prepareCommand,
        InstanceActivationCommand activationCommand) {
}
