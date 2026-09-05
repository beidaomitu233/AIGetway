package com.lightai.client.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 非流式统一响应的业务元数据；不返回 Credential ID。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseTraceInfo(
        String traceId,
        String provider,
        String providerModel,
        String usageSource,
        CostInfo cost,
        long snapshotNo) {
}
