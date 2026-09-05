package com.lightai.runtime.route;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 运行路由服务（BE-019）。
 * 过滤顺序：启用 → 熔断开路径 → 能力（stream/system）→ 上下文；
 * 同优先级内按权重无放回抽取（可控随机源保证可测试与可复算）。
 * 过滤不创建 Attempt、不消耗恢复预算；全部候选被过滤时按缺失维度返回
 * MODEL_CAPABILITY_NOT_SUPPORTED 或 CONTEXT_WINDOW_EXCEEDED。
 */
public class RouteService {

    private final RandomGenerator random;

    public RouteService(RandomGenerator random) {
        this.random = random;
    }

    public CapabilityRequirement.Decision route(List<RouteCandidateView> snapshotCandidates,
                                                CapabilityRequirement requirement) {
        List<CapabilityRequirement.ExcludedCandidate> excluded = new ArrayList<>();
        List<RouteCandidateView> survived = new ArrayList<>();
        boolean capabilityMiss = false;
        boolean contextMiss = false;

        for (RouteCandidateView candidate : snapshotCandidates) {
            if (!candidate.enabled()) {
                excluded.add(new CapabilityRequirement.ExcludedCandidate(candidate, "DISABLED"));
                continue;
            }
            if (candidate.circuitOpen()) {
                excluded.add(new CapabilityRequirement.ExcludedCandidate(candidate, "CIRCUIT_OPEN"));
                continue;
            }
            if (requirement.stream() && !Boolean.TRUE.equals(candidate.supportStream())) {
                excluded.add(new CapabilityRequirement.ExcludedCandidate(candidate, "STREAM_UNSUPPORTED"));
                capabilityMiss = true;
                continue;
            }
            if (requirement.systemMessage() && !Boolean.TRUE.equals(candidate.supportSystemMessage())) {
                excluded.add(new CapabilityRequirement.ExcludedCandidate(candidate, "SYSTEM_UNSUPPORTED"));
                capabilityMiss = true;
                continue;
            }
            long requiredContext = requirement.estimatedInputTokens()
                    + Math.max(requirement.maxTokens(), candidate.maxOutputTokens());
            if (candidate.contextWindow() == null || candidate.contextWindow() < requiredContext) {
                excluded.add(new CapabilityRequirement.ExcludedCandidate(candidate, "CONTEXT_EXCEEDED"));
                contextMiss = true;
                continue;
            }
            survived.add(candidate);
        }

        if (survived.isEmpty()) {
            if (capabilityMiss) {
                throw new LightAiException(ErrorCode.MODEL_CAPABILITY_NOT_SUPPORTED,
                        "没有候选满足流式或 system 能力要求");
            }
            if (contextMiss) {
                throw new LightAiException(ErrorCode.CONTEXT_WINDOW_EXCEEDED,
                        "输入与最大输出超过全部候选上下文");
            }
        }

        return new CapabilityRequirement.Decision(
                orderWithWeights(survived), List.copyOf(excluded));
    }

    /** priority 升序分组；同级内权重无放回抽取，保证同级顺序确定且可复算。 */
    private List<RouteCandidateView> orderWithWeights(List<RouteCandidateView> candidates) {
        List<RouteCandidateView> ordered = new ArrayList<>(candidates.size());
        List<RouteCandidateView> pool = new ArrayList<>(candidates);
        while (!pool.isEmpty()) {
            int highestPriority = pool.stream().mapToInt(RouteCandidateView::priority).min().orElse(0);
            List<RouteCandidateView> sameLevel = new ArrayList<>();
            Set<UUID> levelIds = new HashSet<>();
            for (RouteCandidateView candidate : pool) {
                if (candidate.priority() == highestPriority && levelIds.add(candidate.id())) {
                    sameLevel.add(candidate);
                }
            }
            pool.removeIf(candidate -> levelIds.contains(candidate.id()));
            List<RouteCandidateView> levelOrder = new ArrayList<>(sameLevel.size());
            List<RouteCandidateView> levelPool = new ArrayList<>(sameLevel);
            while (!levelPool.isEmpty()) {
                int totalWeight = levelPool.stream().mapToInt(RouteCandidateView::weight).sum();
                long target = totalWeight == 0
                        ? random.nextLong(levelPool.size())
                        : (long) (random.nextDouble() * totalWeight);
                int picked = 0;
                long accumulated = 0;
                for (int i = 0; i < levelPool.size(); i++) {
                    accumulated += levelPool.get(i).weight();
                    if (accumulated > target) {
                        picked = i;
                        break;
                    }
                    picked = i;
                }
                levelOrder.add(levelPool.remove(picked));
            }
            ordered.addAll(levelOrder);
        }
        return List.copyOf(ordered);
    }
}
