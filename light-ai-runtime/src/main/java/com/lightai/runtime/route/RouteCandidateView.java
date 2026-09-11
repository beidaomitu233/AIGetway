package com.lightai.runtime.route;

import java.util.UUID;

/**
 * 路由候选视图（来自固定活动快照，只读）。
 * circuitOpen 为候选路径的熔断预判（C-008 键：upstream_model_id+channel_credential_id）。
 */
public record RouteCandidateView(
        UUID id,
        UUID aliasId,
        UUID upstreamModelId,
        UUID channelId,
        int priority,
        int weight,
        boolean enabled,
        Boolean supportStream,
        Boolean supportSystemMessage,
        Long contextWindow,
        long maxOutputTokens,
        boolean circuitOpen) {
}
