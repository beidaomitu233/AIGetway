package com.lightai.runtime.local;

import com.lightai.client.LightAiClient;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.chat.ChatPipeline;
import com.lightai.runtime.chat.ReliabilityBudgets;
import com.lightai.runtime.export.TraceExportCoordinator;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.AdapterRegistryPort;
import com.lightai.runtime.ports.CapacityPort;
import com.lightai.runtime.ports.ConfigSnapshotPort;
import com.lightai.runtime.ports.ConfigSnapshotPort.ActiveSnapshot;
import com.lightai.runtime.ports.ConfigSnapshotPort.AliasView;
import com.lightai.runtime.ports.ConfigSnapshotPort.CandidateView;
import com.lightai.runtime.ports.CredentialSecretPort;
import com.lightai.runtime.ports.RoutingPort;
import com.lightai.runtime.secret.SecretManager;
import com.lightai.runtime.trace.InMemoryTraceStore;
import com.lightai.runtime.trace.TraceStore;
import com.lightai.spi.export.TraceExporter;
import com.lightai.spi.provider.ProviderAdapter;
import com.lightai.spi.provider.ProviderChatRequest.SecretHandle;
import com.lightai.spi.secret.ResolvedSecret;
import com.lightai.spi.secret.SecretProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Local Runtime 客户端工厂（BE-050，4.6.2.3）：
 * 组装 snapshot_no=1 的不可变快照，装配内存端口、适配器与协调器。
 */
public final class LocalLightAiClientFactory {

    private LocalLightAiClientFactory() {
    }

    @SuppressWarnings("unchecked")
    public static LightAiClient create(Object definitionObj,
                                       Map<String, Supplier<char[]>> suppliers,
                                       List<?> customAdapters,
                                       List<?> secretProviders,
                                       List<?> traceExporters,
                                       long closeTimeoutMs) {
        if (!(definitionObj instanceof LocalRuntimeDefinition definition)) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "传入的定义必须是 LocalRuntimeDefinition 实例");
        }

        // 离线校验
        LocalRuntimeValidator.validate(definition);

        Map<String, Supplier<char[]>> safeSuppliers = suppliers != null ? new HashMap<>(suppliers) : new HashMap<>();

        // 组装快照 (snapshot_no = 1)
        ActiveSnapshot activeSnapshot = assembleSnapshot(definition);
        ConfigSnapshotPort snapshotPort = () -> activeSnapshot;

        // 运行参数端口
        AccessTokenPort.RuntimeConfigPort runtimeConfigPort = () ->
                Optional.ofNullable(definition.runtimeConfig().defaultAliasId());

        // 路由端口：确定性排序（优先级升序，权重降序）
        RoutingPort routingPort = (alias, request, estimatedInputTokens) -> {
            List<CandidateView> candidates = alias.enabledCandidates().stream()
                    .sorted(Comparator.comparingLong(CandidateView::priority).thenComparingInt(c -> -c.weight()))
                    .toList();
            return new RoutingPort.RoutingResult(candidates, false, false);
        };

        // 容量端口：本地无限制
        CapacityPort capacityPort = CapacityPort.unlimited();

        // 密钥管理 SPI（BE-053）
        List<SecretProvider> spList = new ArrayList<>();
        if (secretProviders != null) {
            for (Object sp : secretProviders) {
                if (sp instanceof SecretProvider provider) {
                    spList.add(provider);
                }
            }
        }
        SecretManager secretManager = new SecretManager(spList);

        // 凭证端口（BE-050 / BE-053）
        Map<String, String> credSecretRefs = new HashMap<>();
        for (LocalRuntimeDefinition.LocalCredentialDefinition cred : definition.credentials()) {
            if (cred.secretRef() != null) {
                credSecretRefs.put(cred.poolId(), cred.secretRef());
                credSecretRefs.put(cred.credentialId(), cred.secretRef());
            }
        }

        CredentialSecretPort credentialPort = (poolId, failoverIndex) -> {
            // 优先从 suppliers 读取
            Supplier<char[]> supplier = safeSuppliers.get(poolId);
            if (supplier == null) {
                supplier = safeSuppliers.get(poolId + "-credential-" + failoverIndex);
            }
            if (supplier != null) {
                return new CredentialSecretPort.ResolvedCredential(poolId + "-credential-" + failoverIndex, supplier::get);
            }

            // 尝试通过 SecretManager 解析 secret_ref
            String ref = credSecretRefs.get(poolId);
            if (ref != null && secretManager.hasProvider()) {
                ResolvedSecret resolved = secretManager.resolveSync(ref);
                return new CredentialSecretPort.ResolvedCredential(poolId + "-credential-" + failoverIndex, resolved::secret);
            }

            throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE, "凭证池 " + poolId + " 无可用密钥供给 (credentialSecretSuppliers 或 SecretProvider)");
        };

        // 适配器注册端口
        Map<String, ProviderAdapter> adapterMap = loadAdapters(customAdapters);
        AdapterRegistryPort adapterRegistry = providerType -> Optional.ofNullable(adapterMap.get(providerType.toUpperCase()));

        // TraceStore：纯内存有界
        TraceStore traceStore = new InMemoryTraceStore();

        // 可靠性预算
        ReliabilityBudgets.Port reliabilityPort = () -> ReliabilityBudgets.DEFAULT;

        long totalTimeout = definition.runtimeConfig().totalTimeoutMs() != null
                ? definition.runtimeConfig().totalTimeoutMs() : 120000L;

        ChatPipeline chatPipeline = new ChatPipeline(
                snapshotPort,
                runtimeConfigPort,
                routingPort,
                capacityPort,
                credentialPort,
                adapterRegistry,
                traceStore,
                reliabilityPort,
                totalTimeout
        );

        // 导出协调器（BE-054）
        List<TraceExporter> teList = new ArrayList<>();
        if (traceExporters != null) {
            for (Object te : traceExporters) {
                if (te instanceof TraceExporter exporter) {
                    teList.add(exporter);
                }
            }
        }
        TraceExportCoordinator exportCoordinator = new TraceExportCoordinator(teList);

        return new LocalLightAiClient(activeSnapshot, chatPipeline, secretManager, exportCoordinator, closeTimeoutMs);
    }

    private static ActiveSnapshot assembleSnapshot(LocalRuntimeDefinition def) {
        Map<String, LocalRuntimeDefinition.LocalModelDefinition> modelMap = new HashMap<>();
        for (LocalRuntimeDefinition.LocalModelDefinition m : def.models()) {
            modelMap.put(m.modelId(), m);
        }

        Map<String, LocalRuntimeDefinition.LocalProviderDefinition> provMap = new HashMap<>();
        for (LocalRuntimeDefinition.LocalProviderDefinition p : def.providers()) {
            provMap.put(p.providerId(), p);
        }

        List<AliasView> aliasViews = new ArrayList<>();
        for (LocalRuntimeDefinition.LocalAliasDefinition aliasDef : def.aliases()) {
            List<CandidateView> candidateViews = new ArrayList<>();
            for (LocalRuntimeDefinition.LocalCandidateDefinition cand : aliasDef.candidates()) {
                LocalRuntimeDefinition.LocalModelDefinition model = modelMap.get(cand.modelId());
                LocalRuntimeDefinition.LocalProviderDefinition prov = provMap.get(model.providerId());

                candidateViews.add(new CandidateView(
                        aliasDef.alias() + "-" + cand.modelId(),
                        prov.providerId(),
                        prov.providerType(),
                        cand.modelId(),
                        model.providerModelId(),
                        cand.poolId(),
                        cand.priority(),
                        cand.weight(),
                        true,
                        prov.providerType().toUpperCase(),
                        model.contextWindow(),
                        model.maxOutputTokens(),
                        model.supportStream(),
                        model.supportSystem(),
                        model.supportTemperature(),
                        model.supportTopP(),
                        model.supportStop(),
                        model.temperatureMin(),
                        model.temperatureMax(),
                        model.topPMin(),
                        model.topPMax(),
                        model.maxStopSequences(),
                        model.defaultTemperature(),
                        model.defaultTopP(),
                        model.defaultMaxTokens(),
                        model.inputPrice(),
                        model.outputPrice(),
                        model.priceUnit(),
                        model.currency()
                ));
            }

            aliasViews.add(new AliasView(
                    aliasDef.aliasId(),
                    aliasDef.alias(),
                    aliasDef.displayName(),
                    aliasDef.enabled(),
                    candidateViews
            ));
        }

        return new ActiveSnapshot(1L, aliasViews);
    }

    private static Map<String, ProviderAdapter> loadAdapters(List<?> customAdapters) {
        Map<String, ProviderAdapter> map = new HashMap<>();

        // 优先加载内置适配器（如果存在）
        tryLoadAdapter("com.lightai.provider.openai.OpenAiAdapter", map);
        tryLoadAdapter("com.lightai.provider.anthropic.AnthropicAdapter", map);
        tryLoadAdapter("com.lightai.provider.gemini.GeminiAdapter", map);
        tryLoadAdapter("com.lightai.provider.deepseek.DeepSeekAdapter", map);

        // 自定义适配器覆盖或补充
        if (customAdapters != null) {
            for (Object obj : customAdapters) {
                if (obj instanceof ProviderAdapter adapter) {
                    map.put(adapter.providerType().toUpperCase(), adapter);
                }
            }
        }

        return map;
    }

    private static void tryLoadAdapter(String className, Map<String, ProviderAdapter> map) {
        try {
            Class<?> clazz = Class.forName(className);
            ProviderAdapter adapter = (ProviderAdapter) clazz.getDeclaredConstructor().newInstance();
            map.put(adapter.providerType().toUpperCase(), adapter);
        } catch (Throwable ignored) {
        }
    }
}