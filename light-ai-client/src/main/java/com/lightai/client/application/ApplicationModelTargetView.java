package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationModelTargetView(
        String id,
        String channelId,
        String upstreamModelId,
        String upstreamModelName,
        int priority,
        int weight,
        String status,
        String policyJson) {
}
