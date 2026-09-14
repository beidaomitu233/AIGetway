package com.lightai.client.application;

/** 应用映射的一个渠道目标。upstream_model_id 为空时允许手工输入真实模型名。 */
public record ApplicationModelTargetCommand(
        String channelId,
        String upstreamModelId,
        String upstreamModelName,
        Integer priority,
        Integer weight,
        String status,
        String policyJson) {
}
