package com.lightai.runtime.local;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Local Runtime 静态配置定义（BE-050，4.6.2.3）：
 * 本地定义无网络、无 DB、无 Admin，用于组装纯内存 snapshot_no=1 快照。
 *
 * V2 资源域：渠道（channel）承接连接语义并直接承载渠道 Key（channel_credential），
 * 上游模型（upstream_model）绑定渠道，候选路径绑定「渠道 + 上游模型」。
 */
public record LocalRuntimeDefinition(
        List<LocalChannelDefinition> channels,
        List<LocalChannelCredentialDefinition> credentials,
        List<LocalUpstreamModelDefinition> models,
        List<LocalAliasDefinition> aliases,
        LocalRuntimeConfig runtimeConfig) {

    public LocalRuntimeDefinition {
        channels = channels != null ? List.copyOf(channels) : List.of();
        credentials = credentials != null ? List.copyOf(credentials) : List.of();
        models = models != null ? List.copyOf(models) : List.of();
        aliases = aliases != null ? List.copyOf(aliases) : List.of();
        runtimeConfig = runtimeConfig != null ? runtimeConfig : LocalRuntimeConfig.DEFAULT;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 渠道定义：providerType 为协议类型目录标识，baseUrl 为上游连接地址。 */
    public record LocalChannelDefinition(
            String channelId,
            String providerType,
            String baseUrl,
            Long timeoutMs) {
    }

    /** 渠道 Key 定义：直挂渠道，secretRef 为空表示由外部 supplier 提供。 */
    public record LocalChannelCredentialDefinition(
            String channelCredentialId,
            String channelId,
            String secretRef) {
    }

    /** 上游模型定义：upstreamModelId 为行主键，modelId 为上游真实模型标识。 */
    public record LocalUpstreamModelDefinition(
            String upstreamModelId,
            String channelId,
            String modelId,
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
            String currency) {

        public static LocalUpstreamModelDefinition simple(String upstreamModelId, String channelId, String modelId) {
            return new LocalUpstreamModelDefinition(
                    upstreamModelId, channelId, modelId,
                    128000L, 4096L, true, true, true, true, true,
                    BigDecimal.ZERO, BigDecimal.valueOf(2.0),
                    BigDecimal.ZERO, BigDecimal.ONE, 4,
                    BigDecimal.ONE, BigDecimal.ONE, 2048L,
                    "0.00", "0.00", 1000, "USD"
            );
        }
    }

    /** 候选定义：绑定渠道与上游模型。 */
    public record LocalCandidateDefinition(
            String upstreamModelId,
            String channelId,
            long priority,
            int weight,
            Long timeoutMs,
            Integer maxRetries) {

        public static LocalCandidateDefinition of(String upstreamModelId, String channelId) {
            return new LocalCandidateDefinition(upstreamModelId, channelId, 1L, 100, 60000L, 1);
        }
    }

    public record LocalAliasDefinition(
            String aliasId,
            String alias,
            String displayName,
            boolean enabled,
            List<LocalCandidateDefinition> candidates) {

        public LocalAliasDefinition {
            candidates = candidates != null ? List.copyOf(candidates) : List.of();
        }

        public static LocalAliasDefinition of(String alias, String upstreamModelId, String channelId) {
            return new LocalAliasDefinition(
                    alias, alias, alias, true,
                    List.of(LocalCandidateDefinition.of(upstreamModelId, channelId))
            );
        }
    }

    public record LocalRuntimeConfig(
            String defaultAliasId,
            Long maxRequestChars,
            Long totalTimeoutMs,
            String timezone) {

        public static final LocalRuntimeConfig DEFAULT = new LocalRuntimeConfig(
                null, 100000L, 120000L, "Asia/Shanghai");
    }

    public static class Builder {
        private final List<LocalChannelDefinition> channels = new ArrayList<>();
        private final List<LocalChannelCredentialDefinition> credentials = new ArrayList<>();
        private final List<LocalUpstreamModelDefinition> models = new ArrayList<>();
        private final List<LocalAliasDefinition> aliases = new ArrayList<>();
        private LocalRuntimeConfig runtimeConfig = LocalRuntimeConfig.DEFAULT;

        public Builder addChannel(String channelId, String providerType, String baseUrl) {
            this.channels.add(new LocalChannelDefinition(channelId, providerType, baseUrl, 60000L));
            return this;
        }

        public Builder addChannel(LocalChannelDefinition channel) {
            this.channels.add(channel);
            return this;
        }

        public Builder addCredential(String channelCredentialId, String channelId, String secretRef) {
            this.credentials.add(new LocalChannelCredentialDefinition(channelCredentialId, channelId, secretRef));
            return this;
        }

        public Builder addCredential(LocalChannelCredentialDefinition credential) {
            this.credentials.add(credential);
            return this;
        }

        public Builder addModel(LocalUpstreamModelDefinition model) {
            this.models.add(model);
            return this;
        }

        public Builder addModel(String upstreamModelId, String channelId, String modelId) {
            this.models.add(LocalUpstreamModelDefinition.simple(upstreamModelId, channelId, modelId));
            return this;
        }

        public Builder addAlias(LocalAliasDefinition alias) {
            this.aliases.add(alias);
            return this;
        }

        public Builder addAlias(String alias, String upstreamModelId, String channelId) {
            this.aliases.add(LocalAliasDefinition.of(alias, upstreamModelId, channelId));
            return this;
        }

        public Builder runtimeConfig(LocalRuntimeConfig config) {
            this.runtimeConfig = config;
            return this;
        }

        public LocalRuntimeDefinition build() {
            return new LocalRuntimeDefinition(channels, credentials, models, aliases, runtimeConfig);
        }
    }
}
