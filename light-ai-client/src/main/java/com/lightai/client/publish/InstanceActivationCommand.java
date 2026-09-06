package com.lightai.client.publish;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 实例激活命令（BACKEND_PLAN 2 协议字典）：验证 ACTIVE 后引用切换并上报 LOADED。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InstanceActivationCommand(
        String publishId,
        long snapshotNo,
        String contentChecksum) {
}
