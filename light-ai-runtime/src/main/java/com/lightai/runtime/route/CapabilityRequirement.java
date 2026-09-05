package com.lightai.runtime.route;

import java.util.List;
import java.util.Optional;

/**
 * 请求能力需求（BE-019 输入）：由统一请求推导，不含消息正文。
 */
public record CapabilityRequirement(boolean stream, boolean systemMessage,
                                    long estimatedInputTokens, long maxTokens) {

    /**
     * 过滤后的路由决策：候选按 priority 升序、同级权重无放回抽取排序。
     * excluded 携带每个被排除候选与原因，过滤阶段不创建 Attempt、不消耗恢复预算。
     */
    public record ExcludedCandidate(RouteCandidateView candidate, String reason) {
    }

    public record Decision(List<RouteCandidateView> ordered, List<ExcludedCandidate> excluded) {

        public Optional<RouteCandidateView> first() {
            return ordered.isEmpty() ? Optional.empty() : Optional.of(ordered.get(0));
        }
    }
}
