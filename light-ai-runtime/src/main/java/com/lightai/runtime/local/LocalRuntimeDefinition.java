package com.lightai.runtime.local;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Local Runtime 静态配置定义（BE-050，4.6.2.3）：
 * 本地定义无网络、无 DB、无 Admin，用于组装纯内存 snapshot_no=1 快照。
 */
public record LocalRuntimeDefinition(
        List<LocalProviderDefinition> providers,
        List<LocalPoolDefinition> pools,
        List<LocalCredentialDefinition> credentials,
        List<LocalModelDefinition> models,
        List<LocalAliasDefinition> aliases,
        LocalRuntimeConfig runtimeConfig) {

    public LocalRuntimeDefinition {
        providers = providers != null ? List.copyOf(providers) : List.of();
        pools = pools != null ? List.copyOf(pools) : List.of();
        credentials = credentials != null ? List.copyOf(credentials) : List.of();
        models = models != null ? List.copyOf(models) : List.of();
        aliases = aliases != null ? List.copyOf(aliases) : List.of();
        runtimeConfig = runtimeConfig != null ? runtimeConfig : LocalRuntimeConfig.DEFAULT;
    }

    public static Builder builder() {
        return new Builder();
    }

    public record LocalProviderDefinition(
            String providerId,
            String providerType,
            String baseUrl,
            Long timeoutMs) {
    }

    public record LocalPoolDefinition(
            String poolId,
            String providerId,
            String selectionStrategy) {
    }

    public record LocalCredentialDefinition(
            String credentialId,
            String poolId,
            String providerId,
            String secretRef) {
    }

    public record LocalModelDefinition(
            String modelId,
            String providerId,
            String providerModelId,
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

        public static LocalModelDefinition simple(String modelId, String providerId, String providerModelId) {
            return new LocalModelDefinition(
                    modelId, providerId, providerModelId,
                    128000L, 4096L, true, true, true, true, true,
                    BigDecimal.ZERO, BigDecimal.valueOf(2.0),
                    BigDecimal.ZERO, BigDecimal.ONE, 4,
                    BigDecimal.ONE, BigDecimal.ONE, 2048L,
                    "0.00", "0.00", 1000, "USD"
            );
        }
    }

    public record LocalCandidateDefinition(
            String modelId,
            String poolId,
            long priority,
            int weight,
            Long timeoutMs,
            Integer maxRetries) {

        public static LocalCandidateDefinition of(String modelId, String poolId) {
            return new LocalCandidateDefinition(modelId, poolId, 1L, 100, 60000L, 1);
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

        public static LocalAliasDefinition of(String alias, String modelId, String poolId) {
            return new LocalAliasDefinition(
                    alias, alias, alias, true,
                    List.of(LocalCandidateDefinition.of(modelId, poolId))
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
        private final List<LocalProviderDefinition> providers = new ArrayList<>();
        private final List<LocalPoolDefinition> pools = new ArrayList<>();
        private final List<LocalCredentialDefinition> credentials = new ArrayList<>();
        private final List<LocalModelDefinition> models = new ArrayList<>();
        private final List<LocalAliasDefinition> aliases = new ArrayList<>();
        private LocalRuntimeConfig runtimeConfig = LocalRuntimeConfig.DEFAULT;

        public Builder addProvider(String providerId, String providerType, String baseUrl) {
            this.providers.add(new LocalProviderDefinition(providerId, providerType, baseUrl, 60000L));
            return this;
        }

        public Builder addProvider(LocalProviderDefinition provider) {
            this.providers.add(provider);
            return this;
        }

        public Builder addPool(String poolId, String providerId) {
            this.pools.add(new LocalPoolDefinition(poolId, providerId, "ROUND_ROBIN"));
            return this;
        }

        public Builder addPool(LocalPoolDefinition pool) {
            this.pools.add(pool);
            return this;
        }

        public Builder addCredential(String credentialId, String poolId, String providerId, String secretRef) {
            this.credentials.add(new LocalCredentialDefinition(credentialId, poolId, providerId, secretRef));
            return this;
        }

        public Builder addCredential(LocalCredentialDefinition credential) {
            this.credentials.add(credential);
            return this;
        }

        public Builder addModel(LocalModelDefinition model) {
            this.models.add(model);
            return this;
        }

        public Builder addModel(String modelId, String providerId, String providerModelId) {
            this.models.add(LocalModelDefinition.simple(modelId, providerId, providerModelId));
            return this;
        }

        public Builder addAlias(LocalAliasDefinition alias) {
            this.aliases.add(alias);
            return this;
        }

        public Builder addAlias(String alias, String modelId, String poolId) {
            this.aliases.add(LocalAliasDefinition.of(alias, modelId, poolId));
            return this;
        }

        public Builder runtimeConfig(LocalRuntimeConfig config) {
            this.runtimeConfig = config;
            return this;
        }

        public LocalRuntimeDefinition build() {
            return new LocalRuntimeDefinition(providers, pools, credentials, models, aliases, runtimeConfig);
        }
    }
}