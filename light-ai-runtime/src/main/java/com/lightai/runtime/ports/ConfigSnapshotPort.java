package com.lightai.runtime.ports;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 活动配置快照端口（BE-P07 发布前为桩）：一次 Trace 固定一个 snapshot_no，
 * 运行中请求保持原快照；新请求禁止从草稿表取路由参数。
 */
public interface ConfigSnapshotPort {

    ActiveSnapshot active();

    /**
     * 是否存在可用的 ACTIVE 快照（PRD 4.6.4.3）。
     * 首次安装使用 snapshot_no=0 的初始快照，因此不能用快照号是否大于 0 判断就绪。
     */
    default boolean hasActiveSnapshot() {
        ActiveSnapshot snapshot = active();
        return snapshot != null;
    }

    /** 当前活动快照的 Alias 与候选装配视图。 */
    record ActiveSnapshot(long snapshotNo, List<AliasView> aliases) {

        public Optional<AliasView> alias(String alias) {
            return aliases.stream().filter(view -> view.alias().equals(alias)).findFirst();
        }
    }

    record AliasView(
            String aliasId,
            String alias,
            String displayName,
            boolean enabled,
            List<CandidateView> candidates) {

        public AliasView {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public List<CandidateView> enabledCandidates() {
            return candidates.stream().filter(CandidateView::enabled).toList();
        }

        /** Alias 能力：至少一个启用候选支持对应能力。 */
        public boolean supportsStream() {
            return enabledCandidates().stream().anyMatch(candidate -> Boolean.TRUE.equals(candidate.supportStream()));
        }
    }

    /** 候选运行视图：模型能力、默认值、价格与 Provider 连接信息一并装配（BE-030 价格快照来源）。 */
    record CandidateView(
            String candidateId,
            String providerId,
            String providerType,
            String modelPk,
            String modelId,
            String poolId,
            long priority,
            int weight,
            boolean enabled,
            String tokenizerFamily,
            Long contextWindow,
            Long maxOutputTokens,
            Boolean supportStream,
            Boolean supportSystem,
            Boolean supportTemperature,
            Boolean supportTopP,
            Boolean supportStop,
            BigDecimal temperatureMin,
            BigDecimal temperatureMax,
            BigDecimal topPMin,
            BigDecimal topPMax,
            Integer maxStopSequences,
            BigDecimal defaultTemperature,
            BigDecimal defaultTopP,
            Long defaultMaxTokens,
            String inputPrice,
            String outputPrice,
            int priceUnit,
            String currency,
            String baseUrl,
            String proxyUrl,
            int connectTimeoutMs,
            int readTimeoutMs,
            Map<String, String> defaultHeaders) {

        public CandidateView {
            defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        }

        public boolean supportsStreamModel() {
            return Boolean.TRUE.equals(supportStream);
        }
    }

    static ConfigSnapshotPort empty() {
        return new ConfigSnapshotPort() {
            @Override
            public ActiveSnapshot active() {
                return new ActiveSnapshot(0, List.of());
            }

            @Override
            public boolean hasActiveSnapshot() {
                return false;
            }
        };
    }

    /** Alias 不存在或未发布。 */
    static LightAiException aliasNotFound(String alias) {
        return new LightAiException(ErrorCode.MODEL_ALIAS_NOT_FOUND, "Alias 不存在或未发布: " + alias);
    }

    static LightAiException aliasDisabled(String alias) {
        return new LightAiException(ErrorCode.MODEL_ALIAS_DISABLED, "Alias 已停用: " + alias);
    }
}
