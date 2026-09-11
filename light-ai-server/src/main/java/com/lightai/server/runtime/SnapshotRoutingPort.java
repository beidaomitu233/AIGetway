package com.lightai.server.runtime;

import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView;
import com.lightai.runtime.ports.RoutingPort;
import com.lightai.runtime.route.CapabilityRequirement;
import com.lightai.runtime.route.RouteCandidateView;
import com.lightai.runtime.route.RouteService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 快照路由端口（BE-019 接线）：能力/上下文过滤 + 优先级与权重排序。
 * 输入为活动快照的启用候选，经 RouteService 过滤排序后按 candidate_id 映射回原视图。
 * 熔断预判：熔断键为 model+credential（C-008），凭证在 Attempt 阶段才确定，
 * 预路由阶段不做熔断过滤；OPEN 熔断的影响由 Attempt 失败与恢复预算承担。
 */
public final class SnapshotRoutingPort implements RoutingPort {

    private final RouteService routeService;

    public SnapshotRoutingPort(RouteService routeService) {
        this.routeService = routeService;
    }

    @Override
    public RoutingResult order(AliasView alias, UnifiedChatRequest request, long estimatedInputTokens) {
        List<CandidateView> enabled = alias.enabledCandidates();
        if (enabled.isEmpty()) {
            return new RoutingResult(List.of(), false, false);
        }
        Map<String, CandidateView> byId = new HashMap<>();
        for (CandidateView candidate : enabled) {
            if (candidate.candidateId() != null) {
                byId.put(candidate.candidateId(), candidate);
            }
        }
        long maxTokens = request.maxTokens() == null ? 0 : request.maxTokens().longValue();
        boolean needSystem = request.messages() != null && request.messages().stream()
                .anyMatch(message -> "system".equals(message.role()));
        CapabilityRequirement requirement = new CapabilityRequirement(
                request.stream(), needSystem, estimatedInputTokens, maxTokens);
        try {
            CapabilityRequirement.Decision decision =
                    routeService.route(toRouteViews(alias, enabled), requirement);
            List<CandidateView> ordered = decision.ordered().stream()
                    .map(view -> byId.get(view.id() == null ? null : view.id().toString()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            return new RoutingResult(ordered, false, false);
        } catch (LightAiException e) {
            if (e.code() == ErrorCode.CONTEXT_WINDOW_EXCEEDED) {
                return new RoutingResult(List.of(), false, true);
            }
            if (e.code() == ErrorCode.MODEL_CAPABILITY_NOT_SUPPORTED) {
                return new RoutingResult(List.of(), true, false);
            }
            throw e;
        }
    }

    private static List<RouteCandidateView> toRouteViews(AliasView alias, List<CandidateView> candidates) {
        return candidates.stream()
                .map(candidate -> new RouteCandidateView(
                        uuid(candidate.candidateId()), uuid(alias.aliasId()),
                        uuid(candidate.modelPk()), uuid(candidate.channelId()),
                        (int) Math.min(Integer.MAX_VALUE, candidate.priority()), candidate.weight(),
                        candidate.enabled(), candidate.supportStream(), candidate.supportSystem(),
                        candidate.contextWindow(),
                        candidate.maxOutputTokens() == null ? 0L : candidate.maxOutputTokens(),
                        false))
                .toList();
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
