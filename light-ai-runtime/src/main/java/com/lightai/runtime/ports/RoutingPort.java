package com.lightai.runtime.ports;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView;
import java.util.List;

/**
 * 路由端口（BE-P04 RouteService 交付后接线）：能力与上下文过滤 + 优先级/权重排序。
 * 过滤不创建 Attempt，不消耗 Fallback 预算。
 */
public interface RoutingPort {

    /**
     * @param estimatedInputTokens 本 Trace 内按 tokenizer family 估算的输入 Token
     * @return 排序后的可用候选；空集时由 allCapabilityFiltered/allContextFiltered 区分错误
     */
    RoutingResult order(AliasView alias, com.lightai.client.chat.UnifiedChatRequest request,
                        long estimatedInputTokens);

    record RoutingResult(List<CandidateView> candidates, boolean allCapabilityFiltered,
                         boolean allContextFiltered) {

        public RoutingResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public LightAiException rejection() {
            if (!candidates.isEmpty()) {
                throw new IllegalStateException("rejection() 仅用于空候选");
            }
            if (allContextFiltered) {
                return new LightAiException(ErrorCode.CONTEXT_WINDOW_EXCEEDED,
                        "输入与最大输出超过全部候选上下文");
            }
            return new LightAiException(ErrorCode.MODEL_CAPABILITY_NOT_SUPPORTED,
                    "没有候选满足流式、system 或参数能力");
        }
    }
}
